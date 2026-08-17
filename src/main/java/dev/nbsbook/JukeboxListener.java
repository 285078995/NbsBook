package dev.nbsbook;

import dev.nbsbook.song.BookUtil;
import dev.nbsbook.song.DecodedSong;
import dev.nbsbook.song.SongCodec;
import dev.nbsbook.song.SongPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Jukebox;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 唱片机播放：手持写有乐谱的书与笔右键唱片机时，将书放入唱片机并播放。
 * 再次右键同一个唱片机时停止并弹出乐谱，行为与普通唱片一致。
 */
public final class JukeboxListener implements Listener {

    private final NbsBookPlugin plugin;
    private final SongPlayer songPlayer;
    private final Map<String, UUID> jukeboxOwners = new HashMap<>();
    private final Map<UUID, String> playerJukeboxes = new HashMap<>();
    private BukkitTask monitorTask;

    public JukeboxListener(NbsBookPlugin plugin) {
        this.plugin = plugin;
        this.songPlayer = plugin.getSongPlayer();
    }

    public void start() {
        // 周期监听唱片机内容，乐谱书离开唱片机时立即停止播放
        monitorTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::monitorJukeboxes, 5L, 5L);
    }

    public void cleanup() {
        if (monitorTask != null) {
            monitorTask.cancel();
            monitorTask = null;
        }
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        Block block = event.getClickedBlock();
        if (block == null || block.getType() != Material.JUKEBOX) {
            return;
        }

        Player player = event.getPlayer();
        Jukebox jukebox = (Jukebox) block.getState();
        Location source = block.getLocation().add(0.5, 1.0, 0.5);
        ItemStack record = jukebox.getRecord();

        // 唱片机里已经放入了乐谱书：再次右键时停止并弹出
        if (record != null && BookUtil.isWritableBook(record)) {
            stopAndEject(event, player, block, jukebox, record, source);
            return;
        }

        if (!player.hasPermission("nbsbook.play")) {
            return;
        }

        ItemStack item = event.getItem();
        if (!BookUtil.isWritableBook(item)) {
            return;
        }

        List<String> pages = BookUtil.readPages(item);
        if (pages == null || pages.isEmpty()) {
            return;
        }

        DecodedSong song;
        try {
            song = SongCodec.decode(pages);
        } catch (SongCodec.CodecException e) {
            // 不是有效乐谱时不干预唱片机的原版交互
            return;
        }

        // 不覆盖唱片机里已有的原版唱片
        if (jukebox.hasRecord()) {
            return;
        }

        String loreName = BookUtil.readNameFromLore(item);
        DecodedSong finalSong = (!loreName.isEmpty() && song.name().isEmpty())
                ? new DecodedSong(loreName, song.author(), song.tempo(), song.events())
                : song;

        // 像放入普通唱片一样：消耗手中的一本乐谱书，并放入唱片机
        ItemStack recordItem = item.clone();
        recordItem.setAmount(1);
        consumeHeldItem(player, event.getHand());
        jukebox.setRecord(recordItem);
        if (!jukebox.update(true)) {
            player.getInventory().addItem(recordItem);
            player.sendMessage(Component.text("无法把乐谱放入唱片机。", NamedTextColor.RED));
            event.setCancelled(true);
            event.setUseInteractedBlock(Event.Result.DENY);
            event.setUseItemInHand(Event.Result.DENY);
            return;
        }

        String key = blockKey(block);
        jukeboxOwners.put(key, player.getUniqueId());
        playerJukeboxes.put(player.getUniqueId(), key);

        songPlayer.playFromJukebox(player, source, finalSong);
        songPlayer.onFinish(player, () -> {
            playerJukeboxes.remove(player.getUniqueId());
            jukeboxOwners.remove(key, player.getUniqueId());
            player.sendMessage(Component.text("唱片机播放结束。", NamedTextColor.GRAY));
        });
        player.sendMessage(Component.text("唱片机开始播放: ", NamedTextColor.GOLD)
                .append(Component.text(finalSong.name().isEmpty() ? "未命名乐谱" : finalSong.name(), NamedTextColor.YELLOW)));

        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        String key = playerJukeboxes.remove(playerId);
        if (key != null) {
            jukeboxOwners.remove(key, playerId);
        }
    }

    private void monitorJukeboxes() {
        for (Map.Entry<String, UUID> entry : new HashMap<>(jukeboxOwners).entrySet()) {
            String key = entry.getKey();
            UUID ownerId = entry.getValue();
            Block block = blockFromKey(key);
            if (block == null) {
                continue;
            }
            if (!block.getWorld().isChunkLoaded(block.getX() >> 4, block.getZ() >> 4)) {
                continue;
            }

            Location source = block.getLocation().add(0.5, 1.0, 0.5);
            if (block.getType() != Material.JUKEBOX) {
                stopIfJukeboxSource(ownerId, source);
                removeMappings(key, ownerId);
                continue;
            }

            Jukebox jukebox = (Jukebox) block.getState();
            ItemStack record = jukebox.getRecord();
            if (record == null || !BookUtil.isWritableBook(record)) {
                stopIfJukeboxSource(ownerId, source);
                removeMappings(key, ownerId);
            }
        }
    }

    private void stopIfJukeboxSource(UUID ownerId, Location source) {
        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner == null) {
            return;
        }
        if (songPlayer.getSourceKind(owner) != SongPlayer.SourceKind.JUKEBOX) {
            return;
        }
        Location activeSource = songPlayer.getSource(owner);
        if (activeSource != null && isSameLocation(activeSource, source)) {
            songPlayer.stopJukeboxPlayback(owner);
        }
    }

    private void removeMappings(String key, UUID ownerId) {
        jukeboxOwners.remove(key);
        if (key.equals(playerJukeboxes.get(ownerId))) {
            playerJukeboxes.remove(ownerId);
        }
    }

    private Block blockFromKey(String key) {
        int colon = key.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String worldName = key.substring(0, colon);
        String[] parts = key.substring(colon + 1).split(",", -1);
        if (parts.length != 3) {
            return null;
        }
        try {
            World world = plugin.getServer().getWorld(worldName);
            if (world == null) {
                return null;
            }
            return world.getBlockAt(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) {
            return;
        }
        Block block = event.getBlock();
        if (block.getType() != Material.JUKEBOX) {
            return;
        }

        String key = blockKey(block);
        UUID ownerId = jukeboxOwners.get(key);
        if (ownerId == null) {
            return;
        }

        Player owner = plugin.getServer().getPlayer(ownerId);
        if (owner != null) {
            Location source = block.getLocation().add(0.5, 1.0, 0.5);
            Location activeSource = songPlayer.getSource(owner);
            if (activeSource != null && isSameLocation(activeSource, source)) {
                songPlayer.stopJukeboxPlayback(owner);
            }
        }

        playerJukeboxes.remove(ownerId);
        jukeboxOwners.remove(key);
    }

    private void stopAndEject(PlayerInteractEvent event, Player player, Block block,
                              Jukebox jukebox, ItemStack record, Location source) {
        String key = blockKey(block);
        UUID ownerId = jukeboxOwners.get(key);
        if (ownerId != null) {
            Player owner = plugin.getServer().getPlayer(ownerId);
            if (owner != null) {
                Location activeSource = songPlayer.getSource(owner);
                if (activeSource != null && isSameLocation(activeSource, source)) {
                    songPlayer.stopJukeboxPlayback(owner);
                }
            }
            playerJukeboxes.remove(ownerId);
        }
        jukeboxOwners.remove(key);

        if (!jukebox.eject()) {
            jukebox.setRecord(null);
            jukebox.update(true);
            if (record != null) {
                block.getWorld().dropItemNaturally(source, record);
            }
        }

        player.sendMessage(Component.text("唱片机已停止，乐谱已弹出。", NamedTextColor.GRAY));
        event.setCancelled(true);
        event.setUseInteractedBlock(Event.Result.DENY);
        event.setUseItemInHand(Event.Result.DENY);
    }

    private static void consumeHeldItem(Player player, EquipmentSlot hand) {
        ItemStack held = player.getInventory().getItem(hand);
        if (held == null) {
            return;
        }
        if (held.getAmount() <= 1) {
            player.getInventory().setItem(hand, null);
        } else {
            held.setAmount(held.getAmount() - 1);
        }
    }

    private static String blockKey(Block block) {
        return block.getWorld().getName() + ":" + block.getX() + "," + block.getY() + "," + block.getZ();
    }

    private static boolean isSameLocation(Location a, Location b) {
        if (a.getWorld() == null || b.getWorld() == null) {
            return false;
        }
        return a.getWorld().equals(b.getWorld()) && a.distanceSquared(b) < 0.01;
    }
}
