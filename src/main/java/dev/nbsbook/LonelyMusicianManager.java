package dev.nbsbook;

import dev.nbsbook.nbs.NbsParser;
import dev.nbsbook.nbs.NbsSong;
import dev.nbsbook.song.BookUtil;
import dev.nbsbook.song.DecodedSong;
import dev.nbsbook.song.SongCodec;
import dev.nbsbook.song.SongPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.*;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.bukkit.loot.LootContext;
import org.bukkit.loot.LootTable;
import org.bukkit.metadata.FixedMetadataValue;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.Vector;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Stream;

/**
 * 孤独的音乐家管理器：低概率将怪物替换为孤独的音乐家。
 *
 * <p>行为规则：</p>
 * <ul>
 *   <li>怪物生成时有低概率替换为孤独的音乐家</li>
 *   <li>外观随机采用一种怪物模型，但对玩家无敌意，且亡灵生物不会在阳光下燃烧</li>
 *   <li>生成时随机携带一首乐谱（来自 musician_songs 文件夹）</li>
 *   <li>玩家进入 36 格范围时开始播放乐谱</li>
 *   <li>播放时周围所有生物失去对该玩家的仇恨，并给予玩家生命恢复</li>
 *   <li>播放完成后，5 格内对视时丢出乐谱 + 试炼宝库奖励</li>
 *   <li>始终看向最近玩家的眼睛</li>
 *   <li>玩家离开 128 格后音乐家消失</li>
 *   <li>128 格内只允许存在一个孤独的音乐家</li>
 * </ul>
 */
public final class LonelyMusicianManager implements Listener {

    /** 怪物生成时替换为孤独的音乐家的概率 */
    private static final double SPAWN_CHANCE = 0.02;
    /** 音乐家靠近触发距离 */
    private static final double APPROACH_DISTANCE = 36.0;
    /** 音乐家消失距离 */
    private static final double DESPAWN_DISTANCE = 128.0;
    /** 音乐家与玩家对视 + 交付的距离 */
    private static final double DELIVERY_DISTANCE = 5.0;

    /** 孤独的音乐家随机采用的怪物模型 */
    private static final EntityType[] MUSICIAN_TYPES = {
            EntityType.ZOMBIE,
            EntityType.SKELETON,
            EntityType.CREEPER,
            EntityType.SPIDER,
            EntityType.WITCH,
            EntityType.PILLAGER,
            EntityType.VINDICATOR,
            EntityType.EVOKER,
            EntityType.HUSK,
            EntityType.STRAY,
            EntityType.DROWNED,
            EntityType.BOGGED,
            EntityType.WITHER_SKELETON,
            EntityType.CAVE_SPIDER,
            EntityType.ENDERMITE,
            EntityType.SILVERFISH
    };

    /** 没有手、无法手持物品的怪物模型，乐谱改为戴在头上 */
    private static final Set<EntityType> HANDLESS_MUSICIAN_TYPES = Set.of(
            EntityType.CREEPER,
            EntityType.SPIDER,
            EntityType.CAVE_SPIDER,
            EntityType.ENDERMITE,
            EntityType.SILVERFISH
    );

    /** 音乐家实体的 metadata key，标记是否是孤独的音乐家 */
    private static final String META_KEY = "nbsbook_lonely_musician";
    /** 音乐家持有的乐谱文件路径 metadata key */
    private static final String META_SONG_KEY = "nbsbook_musician_song";
    /** 音乐家是否已开始播放的标记 */
    private static final String META_PLAYED_KEY = "nbsbook_musician_played";
    /** 音乐家是否已交付的标记 */
    private static final String META_DELIVERED_KEY = "nbsbook_musician_delivered";

    private final NbsBookPlugin plugin;
    private final Set<UUID> activeMusicians = new HashSet<>();
    /** 正在为玩家演奏的音乐家：玩家 UUID -> 音乐家实体 UUID。 */
    private final Map<UUID, UUID> musicianPlayers = new HashMap<>();
    /** 已经被音乐家交付过的乐谱路径；全部交付完后重置。 */
    private final Set<String> deliveredSongPaths = new HashSet<>();
    private boolean enabled;
    private BukkitTask tickTask;

    public LonelyMusicianManager(NbsBookPlugin plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("musician.enabled", true);
    }

    public void start() {
        // 每 10 tick (0.5秒) 执行一次：更新音乐家朝向、检测距离、触发播放
        tickTask = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick, 20L, 10L);
    }

    public void cleanup() {
        if (tickTask != null) {
            tickTask.cancel();
        }
        // 清理所有孤独的音乐家
        for (World world : plugin.getServer().getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity.hasMetadata(META_KEY)) {
                    entity.remove();
                }
            }
        }
        activeMusicians.clear();
        musicianPlayers.clear();
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
        plugin.getConfig().set("musician.enabled", enabled);
        plugin.saveConfig();
    }

    // ─── 怪物替换 ───

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCreatureSpawn(CreatureSpawnEvent event) {
        if (event.getSpawnReason() == CreatureSpawnEvent.SpawnReason.CUSTOM) {
            return;
        }
        if (!enabled) {
            return;
        }
        LivingEntity entity = event.getEntity();
        // 只替换敌对生物
        if (!(entity instanceof Monster)) {
            return;
        }
        // 低概率触发
        if (ThreadLocalRandom.current().nextDouble() >= SPAWN_CHANCE) {
            return;
        }
        // 128 格内不能有其他孤独的音乐家
        Location loc = entity.getLocation();
        for (UUID musicianId : activeMusicians) {
            Entity existing = plugin.getServer().getEntity(musicianId);
            if (existing != null && existing.getWorld().equals(loc.getWorld())
                    && existing.getLocation().distanceSquared(loc) < DESPAWN_DISTANCE * DESPAWN_DISTANCE) {
                return;
            }
        }
        // 需要有可用的乐谱
        List<Path> songs = listMusicianSongs();
        if (songs.isEmpty()) {
            return;
        }

        // 取消原始生成，替换为孤独的音乐家
        event.setCancelled(true);
        Location spawnLoc = loc.clone();
        plugin.getServer().getScheduler().runTask(plugin, () -> spawnRandomMusician(spawnLoc, songs));
    }

    private void spawnRandomMusician(Location loc, List<Path> songs) {
        Path song = pickRandomSong(songs);
        if (song == null) {
            return;
        }
        spawnMusician(loc, randomMusicianType(), song);
    }

    private String spawnMusician(Location loc, EntityType type, Path songFile) {
        World world = loc.getWorld();
        if (world == null) {
            return "无法获取生成世界";
        }
        if (songFile == null) {
            return "未指定乐谱";
        }
        if (isSongDelivered(songFile)) {
            List<Path> songs = listMusicianSongs();
            if (allCurrentSongsDelivered(songs)) {
                deliveredSongPaths.clear();
            } else {
                return "该乐谱已被音乐家使用过，请等待所有乐谱都用完后重置";
            }
        }

        LivingEntity musician;
        try {
            musician = (LivingEntity) world.spawnEntity(loc, type);
        } catch (RuntimeException e) {
            return "无法生成模型 " + type + ": " + e.getMessage();
        }

        musician.customName(Component.text("♪ 孤独的音乐家 ♪", NamedTextColor.GRAY));
        musician.setCustomNameVisible(true);

        // 虽然是怪物模型，但要保持非敌意：关闭 AI，让它不会主动攻击或走动
        musician.setAI(false);
        musician.setPersistent(true);

        // 亡灵类怪物不要在阳光下燃烧
        if (musician instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(false);
        } else if (musician instanceof AbstractSkeleton skeleton) {
            skeleton.setShouldBurnInDay(false);
        }
        musician.setFireTicks(0);

        // 保持正常体型
        if (musician.getAttribute(Attribute.SCALE) != null) {
            musician.getAttribute(Attribute.SCALE).setBaseValue(1.0);
        }

        // 手持乐谱；没有手的怪物把乐谱戴在头上
        equipSheetMusic(musician, type);

        // 标记
        musician.setMetadata(META_KEY, new FixedMetadataValue(plugin, true));
        musician.setMetadata(META_SONG_KEY, new FixedMetadataValue(plugin, songFile.toString()));
        musician.setMetadata(META_PLAYED_KEY, new FixedMetadataValue(plugin, false));
        musician.setMetadata(META_DELIVERED_KEY, new FixedMetadataValue(plugin, false));
        activeMusicians.add(musician.getUniqueId());
        plugin.getLogger().info("孤独的音乐家已生成于 " + formatLoc(loc) + "，模型: " + type + "，携带乐谱: " + songFile.getFileName());
        return null;
    }

    private static void equipSheetMusic(LivingEntity musician, EntityType type) {
        EntityEquipment equipment = musician.getEquipment();
        if (equipment == null) {
            return;
        }
        ItemStack sheetMusic = createSheetMusic();
        if (HANDLESS_MUSICIAN_TYPES.contains(type)) {
            equipment.setHelmet(sheetMusic);
            equipment.setHelmetDropChance(0f);
        } else {
            equipment.setItemInMainHand(sheetMusic);
            equipment.setItemInMainHandDropChance(0f);
        }
    }

    private static ItemStack createSheetMusic() {
        ItemStack sheetMusic = new ItemStack(Material.BOOK);
        var meta = sheetMusic.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text("♪ 乐谱 ♪", NamedTextColor.GOLD)
                    .decoration(TextDecoration.ITALIC, false));
            sheetMusic.setItemMeta(meta);
        }
        return sheetMusic;
    }

    /**
     * 手动在指定位置召唤孤独的音乐家（供 /nbs summon 命令调用）。
     *
     * @return 成功时返回 null，失败时返回错误信息
     */
    public String summonMusician(Location loc) {
        Path song = randomMusicianSong();
        if (song == null) {
            return "musician_songs 目录为空，请先放入 .nbs 乐谱文件";
        }
        return summonMusician(loc, randomMusicianType(), song);
    }

    public String summonMusician(Location loc, EntityType type, Path songFile) {
        return spawnMusician(loc, type, songFile);
    }

    public EntityType randomMusicianType() {
        return MUSICIAN_TYPES[ThreadLocalRandom.current().nextInt(MUSICIAN_TYPES.length)];
    }

    public Path randomMusicianSong() {
        return pickRandomSong(listMusicianSongs());
    }

    /**
     * 选择一首尚未交付过的乐谱。
     * 如果当前目录中的所有乐谱都已被交付过，则重置交付记录后再选。
     */
    private Path pickRandomSong(List<Path> songs) {
        if (songs.isEmpty()) {
            return null;
        }
        if (allCurrentSongsDelivered(songs)) {
            deliveredSongPaths.clear();
        }
        List<Path> available = new ArrayList<>();
        for (Path song : songs) {
            if (!isSongDelivered(song)) {
                available.add(song);
            }
        }
        if (available.isEmpty()) {
            deliveredSongPaths.clear();
            available.addAll(songs);
        }
        return available.get(ThreadLocalRandom.current().nextInt(available.size()));
    }

    public EntityType resolveMusicianType(String name) {
        for (EntityType type : MUSICIAN_TYPES) {
            if (type.name().equalsIgnoreCase(name)) {
                return type;
            }
        }
        return null;
    }

    public List<String> listMusicianTypeNames() {
        return Arrays.stream(MUSICIAN_TYPES)
                .map(Enum::name)
                .map(String::toLowerCase)
                .sorted()
                .toList();
    }

    public Path resolveMusicianSong(String requested) {
        if (requested.contains("/") || requested.contains("\\") || requested.contains("..")) {
            return null;
        }
        Path dir = plugin.getMusicianSongsDirectory();

        Path exact = dir.resolve(requested);
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        if (!requested.toLowerCase().endsWith(".nbs")) {
            Path withExt = dir.resolve(requested + ".nbs");
            if (Files.isRegularFile(withExt)) {
                return withExt;
            }
        }

        try (Stream<Path> stream = Files.list(dir)) {
            Path found = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equalsIgnoreCase(requested))
                    .findFirst()
                    .orElse(null);
            if (found != null) {
                return found;
            }
        } catch (IOException ignored) {
        }

        if (!requested.toLowerCase().endsWith(".nbs")) {
            String target = (requested + ".nbs").toLowerCase();
            try (Stream<Path> stream = Files.list(dir)) {
                return stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().equals(target))
                        .findFirst()
                        .orElse(null);
            } catch (IOException ignored) {
            }
        }

        return null;
    }

    // ─── 每 tick 更新 ───

    private void tick() {
        Set<UUID> toRemove = new HashSet<>();

        for (UUID musicianId : activeMusicians) {
            Entity entity = plugin.getServer().getEntity(musicianId);
            if (entity == null || entity.isDead()) {
                toRemove.add(musicianId);
                continue;
            }
            if (!entity.isValid()) {
                toRemove.add(musicianId);
                continue;
            }

            LivingEntity musician = (LivingEntity) entity;
            Player nearest = findNearestPlayer(musician);

            if (nearest == null) {
                toRemove.add(musicianId);
                musician.remove();
                continue;
            }

            double distSq = musician.getLocation().distanceSquared(nearest.getLocation());
            double dist = Math.sqrt(distSq);

            // 超过 128 格：消失
            if (dist > DESPAWN_DISTANCE) {
                musician.remove();
                toRemove.add(musicianId);
                continue;
            }

            // 始终看向最近玩家的眼睛
            lookAt(musician, nearest.getEyeLocation());

            // 36 格内且未播放过：触发播放
            if (dist <= APPROACH_DISTANCE && !musician.getMetadata(META_PLAYED_KEY).get(0).asBoolean()) {
                triggerPlayback(musician, nearest);
            }
        }

        activeMusicians.removeAll(toRemove);
    }

    // ─── 播放触发 ───

    private void triggerPlayback(LivingEntity musician, Player player) {
        List<org.bukkit.metadata.MetadataValue> songMeta = musician.getMetadata(META_SONG_KEY);
        if (songMeta.isEmpty()) {
            return;
        }
        Path songFile = Path.of(songMeta.get(0).asString());

        NbsSong nbsSong;
        try {
            nbsSong = NbsParser.parse(songFile);
        } catch (IOException | RuntimeException e) {
            plugin.getLogger().warning("孤独的音乐家无法加载乐谱 " + songFile.getFileName() + ": " + e.getMessage());
            return;
        }

        List<String> pages;
        try {
            pages = SongCodec.encode(nbsSong);
        } catch (SongCodec.CodecException e) {
            plugin.getLogger().warning("孤独的音乐家无法编码乐谱 " + songFile.getFileName() + ": " + e.getMessage());
            return;
        }

        // 解码页面获取 TickEvent
        DecodedSong decoded;
        try {
            decoded = SongCodec.decode(pages);
        } catch (SongCodec.CodecException e) {
            plugin.getLogger().warning("孤独的音乐家无法解码乐谱 " + songFile.getFileName() + ": " + e.getMessage());
            return;
        }

        // 标记已播放
        musician.setMetadata(META_PLAYED_KEY, new FixedMetadataValue(plugin, true));

        // 清除周围生物对该玩家的仇恨
        clearHostility(musician, player);

        // 给予玩家生命恢复
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 600, 1, true, true));

        // 播放乐谱
        musician.setInvulnerable(true);
        SongPlayer songPlayer = plugin.getSongPlayer();
        songPlayer.playAt(player, musician.getLocation(), decoded);
        musicianPlayers.put(player.getUniqueId(), musician.getUniqueId());

        // 注册播放完成回调：检查交付条件
        songPlayer.onFinish(player, () -> {
            musicianPlayers.remove(player.getUniqueId());
            if (musician.isValid()) {
                musician.setInvulnerable(false);
            }
            tryDeliver(musician, player);
        });
    }

    /** 停止正在为指定玩家演奏的音乐家，并恢复其可受伤状态。 */
    public boolean stopPlaybackFor(Player player) {
        UUID musicianId = musicianPlayers.remove(player.getUniqueId());
        if (musicianId != null) {
            Entity entity = plugin.getServer().getEntity(musicianId);
            if (entity instanceof LivingEntity musician && musician.isValid()) {
                musician.setInvulnerable(false);
            }
        }
        return plugin.getSongPlayer().stopMusicianPlayback(player);
    }

    // ─── 试炼宝库奖励 ───

    private void tryDeliver(LivingEntity musician, Player player) {
        if (musician.isDead() || !musician.isValid()) {
            return;
        }
        if (musician.getMetadata(META_DELIVERED_KEY).get(0).asBoolean()) {
            return;
        }

        plugin.getServer().getScheduler().runTaskTimer(plugin, (task) -> {
            if (musician.isDead() || !musician.isValid() || !player.isOnline()) {
                task.cancel();
                return;
            }

            double dist = musician.getLocation().distance(player.getLocation());
            // 看向玩家
            lookAt(musician, player.getEyeLocation());

            if (dist <= DELIVERY_DISTANCE && isLookingAt(musician, player)) {
                task.cancel();
                deliver(musician, player);
            }
        }, 10L, 5L);
    }

    private void deliver(LivingEntity musician, Player player) {
        musician.setMetadata(META_DELIVERED_KEY, new FixedMetadataValue(plugin, true));

        // 生成乐谱书
        List<org.bukkit.metadata.MetadataValue> songMeta = musician.getMetadata(META_SONG_KEY);
        if (!songMeta.isEmpty()) {
            Path songFile = Path.of(songMeta.get(0).asString());
            try {
                NbsSong nbsSong = NbsParser.parse(songFile);
                List<String> pages = SongCodec.encode(nbsSong);
                pages = BookUtil.addLeadingBlankPages(pages, plugin.getBookBlankPagesBefore());
                String songName = nbsSong.getName().isEmpty()
                        ? stripExtension(songFile.getFileName().toString())
                        : nbsSong.getName();
                DecodedSong decoded = new DecodedSong(songName, nbsSong.getAuthor(), nbsSong.getTempo(), List.of());
                ItemStack book = BookUtil.writeSongBook(decoded, pages, songName);
                // 丢出乐谱书
                player.getWorld().dropItemNaturally(player.getLocation(), book);
                markSongDelivered(songFile);
            } catch (IOException | RuntimeException e) {
                plugin.getLogger().warning("孤独的音乐家无法交付乐谱: " + e.getMessage());
            }
        }

        // 从试炼宝库奖励中抽取一件
        ItemStack reward = generateTrialVaultLoot(player);
        if (reward != null) {
            player.getWorld().dropItemNaturally(player.getLocation(), reward);
        }

        player.sendMessage(Component.text("♪ 孤独的音乐家赠予了你一份礼物！", NamedTextColor.LIGHT_PURPLE));

        // 延迟后移除音乐家
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            musician.remove();
            activeMusicians.remove(musician.getUniqueId());
        }, 40L);
    }

    // ─── 试炼宝库奖励 ───

    private ItemStack generateTrialVaultLoot(Player player) {
        try {
            // 使用试炼宝库的战利品表
            NamespacedKey key = NamespacedKey.minecraft("chests/trial_chambers/reward");
            LootTable lootTable = plugin.getServer().getLootTable(key);
            if (lootTable == null) {
                return null;
            }
            LootContext context = new LootContext.Builder(player.getLocation())
                    .lootedEntity(player)
                    .killer(player)
                    .build();
            Collection<ItemStack> loot = lootTable.populateLoot(ThreadLocalRandom.current(), context);
            if (loot.isEmpty()) {
                return null;
            }
            return loot.iterator().next();
        } catch (Exception e) {
            plugin.getLogger().warning("无法生成试炼宝库奖励: " + e.getMessage());
            return null;
        }
    }

    // ─── 仇恨清除 ───

    private void clearHostility(LivingEntity musician, Player player) {
        double radius = 48.0;
        for (Entity entity : musician.getNearbyEntities(radius, radius, radius)) {
            if (entity instanceof Mob mob) {
                if (mob.getTarget() != null && mob.getTarget().getUniqueId().equals(player.getUniqueId())) {
                    mob.setTarget(null);
                }
            }
        }
    }

    // ─── 朝向与对视检测 ───

    private void lookAt(LivingEntity entity, Location target) {
        Location eye = entity.getEyeLocation();
        Vector dir = target.toVector().subtract(eye.toVector()).normalize();
        float yaw = (float) Math.toDegrees(Math.atan2(-dir.getX(), dir.getZ()));
        float pitch = (float) -Math.toDegrees(Math.asin(dir.getY()));
        entity.setRotation(yaw, Math.max(-90f, Math.min(90f, pitch)));
    }

    private boolean isLookingAt(LivingEntity entity, Player player) {
        Vector entityDir = entity.getEyeLocation().getDirection().normalize();
        Vector toPlayer = player.getEyeLocation().toVector().subtract(entity.getEyeLocation().toVector()).normalize();
        double dot = entityDir.dot(toPlayer);
        // 点积 > 0.85 表示大致朝向玩家（约 30 度内）
        if (dot < 0.85) {
            return false;
        }
        // 同时检查玩家是否看向音乐家
        Vector playerDir = player.getEyeLocation().getDirection().normalize();
        Vector toEntity = entity.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
        return playerDir.dot(toEntity) > 0.85;
    }

    // ─── 工具方法 ───

    private Player findNearestPlayer(LivingEntity entity) {
        Player nearest = null;
        double nearestDistSq = Double.MAX_VALUE;
        for (Player player : entity.getWorld().getPlayers()) {
            double distSq = entity.getLocation().distanceSquared(player.getLocation());
            if (distSq < nearestDistSq) {
                nearestDistSq = distSq;
                nearest = player;
            }
        }
        return nearest;
    }

    public List<Path> listMusicianSongs() {
        Path dir = plugin.getMusicianSongsDirectory();
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    private void markSongDelivered(Path songFile) {
        deliveredSongPaths.add(normalizeSongPath(songFile));
    }

    private boolean isSongDelivered(Path songFile) {
        return deliveredSongPaths.contains(normalizeSongPath(songFile));
    }

    private boolean allCurrentSongsDelivered(List<Path> songs) {
        return !songs.isEmpty() && songs.stream().allMatch(this::isSongDelivered);
    }

    private static String normalizeSongPath(Path path) {
        return path.toAbsolutePath().normalize().toString();
    }

    private static String stripExtension(String fileName) {
        if (fileName.toLowerCase().endsWith(".nbs")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    private static String formatLoc(Location loc) {
        return String.format("(%d, %d, %d) @ %s",
                loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(),
                loc.getWorld() != null ? loc.getWorld().getName() : "?");
    }
}
