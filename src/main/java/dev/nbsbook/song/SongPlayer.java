package dev.nbsbook.song;

import dev.nbsbook.NbsBookPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 播放引擎：按乐谱逐刻播放音符盒声音，同时生成音符粒子。
 * 功能定位与 NoteBot 模组相同，只是乐谱来源是书与笔。
 */
public final class SongPlayer {

    /** 默认粒子生成高度：从音源位置向上偏移，用于玩家和音乐家。 */
    private static final double DEFAULT_PARTICLE_HEIGHT = 2.2;

    /** 播放来源类型，用于区分 /nbs stop、音乐家与唱片机的停止控制。 */
    public enum SourceKind {
        PLAYER,
        MUSICIAN,
        JUKEBOX
    }

    /** NBS 原版乐器编号 → Minecraft 音符盒声音。 */
    private static final Sound[] INSTRUMENTS = {
            Sound.BLOCK_NOTE_BLOCK_HARP,          // 0 钢琴
            Sound.BLOCK_NOTE_BLOCK_BASS,          // 1 低音贝斯
            Sound.BLOCK_NOTE_BLOCK_BASEDRUM,      // 2 底鼓
            Sound.BLOCK_NOTE_BLOCK_SNARE,         // 3 军鼓
            Sound.BLOCK_NOTE_BLOCK_HAT,           // 4 击鼓沿
            Sound.BLOCK_NOTE_BLOCK_GUITAR,        // 5 吉他
            Sound.BLOCK_NOTE_BLOCK_FLUTE,         // 6 长笛
            Sound.BLOCK_NOTE_BLOCK_BELL,          // 7 铃铛
            Sound.BLOCK_NOTE_BLOCK_CHIME,         // 8 风铃
            Sound.BLOCK_NOTE_BLOCK_XYLOPHONE,     // 9 木琴
            Sound.BLOCK_NOTE_BLOCK_IRON_XYLOPHONE,// 10 铁木琴
            Sound.BLOCK_NOTE_BLOCK_COW_BELL,      // 11 牛铃
            Sound.BLOCK_NOTE_BLOCK_DIDGERIDOO,    // 12  didgeridoo
            Sound.BLOCK_NOTE_BLOCK_BIT,           // 13 方波
            Sound.BLOCK_NOTE_BLOCK_BANJO,         // 14 班卓琴
            Sound.BLOCK_NOTE_BLOCK_PLING          // 15 电钢琴
    };

    private final NbsBookPlugin plugin;
    private final Map<UUID, SongTask> active = new HashMap<>();
    private final Map<UUID, Runnable> finishCallbacks = new HashMap<>();

    public SongPlayer(NbsBookPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * 注册播放完成回调。当该玩家的歌曲播放结束时调用。
     * 回调执行后自动移除。
     */
    public void onFinish(Player player, Runnable callback) {
        finishCallbacks.put(player.getUniqueId(), callback);
    }

    /**
     * 开始播放。若玩家已有正在播放的乐谱，先停止旧的。
     */
    public void play(Player player, DecodedSong song) {
        startPlayback(player, player.getLocation(), song, true, DEFAULT_PARTICLE_HEIGHT, SourceKind.PLAYER);
    }

    /**
     * 以指定位置为音源播放乐谱（例如由音乐家演奏）。
     * 播放位置独立于玩家，且不向玩家发送开始/结束提示。
     */
    public void playAt(Player listener, Location source, DecodedSong song) {
        startPlayback(listener, source, song, false, DEFAULT_PARTICLE_HEIGHT, SourceKind.MUSICIAN);
    }

    /**
     * 以唱片机位置为音源播放乐谱。
     * 粒子直接出现在唱片机上方，避免和普通播放一样再向上偏移。
     */
    public void playFromJukebox(Player listener, Location source, DecodedSong song) {
        startPlayback(listener, source, song, false, 0.0, SourceKind.JUKEBOX);
    }

    private void startPlayback(Player listener, Location source, DecodedSong song, boolean announce,
                               double particleHeightOffset, SourceKind sourceKind) {
        stop(listener);
        SongTask task = new SongTask(listener, source, song, announce, particleHeightOffset, sourceKind);
        active.put(listener.getUniqueId(), task);
        double tempo = song.tempo() <= 0 ? 10.0 : song.tempo();
        double seconds = Math.max(0, song.events().get(0).delta() - 1) / tempo;
        task.scheduleNext(Math.max(1L, Math.round(seconds * 20.0)));
        if (announce) {
            listener.sendMessage(Component.text("开始播放: ", NamedTextColor.GOLD)
                    .append(Component.text(song.name().isEmpty() ? "未命名乐谱" : song.name(), NamedTextColor.YELLOW)));
        }
    }

    /**
     * 停止玩家当前的播放。
     *
     * @return 是否确实停止了正在播放的乐谱
     */
    public boolean stop(Player player) {
        SongTask task = active.remove(player.getUniqueId());
        if (task == null) {
            return false;
        }
        task.cancel();
        finishCallbacks.remove(player.getUniqueId());
        return true;
    }

    /** 停止玩家自己通过 /nbs play 发起的播放。 */
    public boolean stopPlayerPlayback(Player player) {
        return stopKind(player, SourceKind.PLAYER);
    }

    /** 停止正在为玩家演奏的音乐家播放。 */
    public boolean stopMusicianPlayback(Player player) {
        return stopKind(player, SourceKind.MUSICIAN);
    }

    /** 停止玩家通过唱片机发起的播放。 */
    public boolean stopJukeboxPlayback(Player player) {
        return stopKind(player, SourceKind.JUKEBOX);
    }

    private boolean stopKind(Player player, SourceKind kind) {
        SongTask task = active.get(player.getUniqueId());
        if (task == null || task.sourceKind != kind) {
            return false;
        }
        active.remove(player.getUniqueId());
        task.cancel();
        finishCallbacks.remove(player.getUniqueId());
        return true;
    }

    public boolean isPlaying(Player player) {
        return active.containsKey(player.getUniqueId());
    }

    /** 返回玩家当前播放任务的音源位置；未在播放时返回 null。 */
    public Location getSource(Player player) {
        SongTask task = active.get(player.getUniqueId());
        return task == null ? null : task.source.clone();
    }

    /** 返回玩家当前播放的来源类型；未在播放时返回 null。 */
    public SourceKind getSourceKind(Player player) {
        SongTask task = active.get(player.getUniqueId());
        return task == null ? null : task.sourceKind;
    }

    /** 服务器关闭或插件卸载时停止全部播放。 */
    public void stopAll() {
        for (SongTask task : active.values()) {
            task.cancel();
        }
        active.clear();
        finishCallbacks.clear();
    }

    /** 玩家退出时调用。 */
    public void onQuit(Player player) {
        SongTask task = active.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        finishCallbacks.remove(player.getUniqueId());
    }

    /**
     * 单个播放任务：按事件链式调度，每个事件播放一个刻上的所有音符。
     */
    private final class SongTask {

        private final Player listener;
        private final Location source;
        private final DecodedSong song;
        private final boolean announce;
        private final double particleHeightOffset;
        private final SourceKind sourceKind;
        private int index = 0;
        private BukkitTask scheduled;
        private boolean cancelled = false;

        SongTask(Player listener, Location source, DecodedSong song, boolean announce,
                 double particleHeightOffset, SourceKind sourceKind) {
            this.listener = listener;
            this.source = source;
            this.song = song;
            this.announce = announce;
            this.particleHeightOffset = particleHeightOffset;
            this.sourceKind = sourceKind;
        }

        void scheduleNext(long delayTicks) {
            if (cancelled) {
                return;
            }
            scheduled = plugin.getServer().getScheduler()
                    .runTaskLater(plugin, this::run, delayTicks);
        }

        void cancel() {
            cancelled = true;
            if (scheduled != null) {
                scheduled.cancel();
            }
        }

        private void run() {
            if (cancelled || !listener.isOnline()) {
                return;
            }
            List<DecodedSong.TickEvent> events = song.events();
            if (index >= events.size()) {
                finish();
                return;
            }
            DecodedSong.TickEvent event = events.get(index++);
            for (DecodedSong.NoteEvent note : event.notes()) {
                playNote(note);
            }
            if (index >= events.size()) {
                finish();
                return;
            }
            // 把乐谱刻间隔换算成服务器刻（20 tick/秒）
            double seconds = events.get(index).delta() / song.tempo();
            long delay = Math.max(1L, Math.round(seconds * 20.0));
            scheduleNext(delay);
        }

        private void finish() {
            active.remove(listener.getUniqueId());
            if (announce) {
                listener.sendMessage(Component.text("乐谱播放结束。", NamedTextColor.GRAY));
            }
            Runnable cb = finishCallbacks.remove(listener.getUniqueId());
            if (cb != null) {
                cb.run();
            }
        }

        private void playNote(DecodedSong.NoteEvent note) {
            int key = foldIntoRange(note.key());
            float pitch = (float) Math.pow(2.0, (key - 45) / 12.0);
            float volume = Math.max(0.1f, note.velocity() / 100f) * 2f;
            Sound sound = note.instrument() >= 0 && note.instrument() < INSTRUMENTS.length
                    ? INSTRUMENTS[note.instrument()]
                    : INSTRUMENTS[0];

            World world = source.getWorld();
            if (world == null) {
                return;
            }
            world.playSound(source, sound, SoundCategory.RECORDS, volume, pitch);

            // 音符粒子：x 偏移量决定颜色（对应音高）
            double hue = Math.max(0.0, Math.min(1.0, (key - 33) / 24.0));
            world.spawnParticle(Particle.NOTE,
                    source.clone().add(0, particleHeightOffset, 0), 0, hue, 0, 0, 1.0);
        }

        /**
         * 把 NBS 音高（0-87）折叠进音符盒可播放的两个八度（33-57）。
         * 超出范围的音按八度平移，而不是简单截断，尽量保留旋律。
         */
        private int foldIntoRange(int key) {
            while (key < 33) {
                key += 12;
            }
            while (key > 57) {
                key -= 12;
            }
            return key;
        }
    }
}
