package dev.nbsbook;

import dev.nbsbook.song.SongPlayer;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * NbsBook 主类。
 *
 * <p>功能：</p>
 * <ol>
 *   <li>读取指定 .nbs 文件，将所选音轨转换为文字形式（含音高、间隔）写入书与笔；</li>
 *   <li>读取书与笔中的内容反推乐谱并逐刻播放（类似 NoteBot，乐谱来源为书本）。</li>
 * </ol>
 */
public final class NbsBookPlugin extends JavaPlugin {

    private SongPlayer songPlayer;
    private LonelyMusicianManager lonelyMusicianManager;
    private JukeboxListener jukeboxListener;
    private Path nbsDirectory;
    private Path musicianSongsDirectory;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.songPlayer = new SongPlayer(this);
        this.nbsDirectory = getDataFolder().toPath().resolve("nbs");
        this.musicianSongsDirectory = getDataFolder().toPath().resolve("musician_songs");
        try {
            Files.createDirectories(nbsDirectory);
            Files.createDirectories(musicianSongsDirectory);
        } catch (IOException e) {
            getLogger().severe("无法创建目录: " + e.getMessage());
        }

        PluginCommand command = getCommand("nbs");
        if (command != null) {
            NbsCommand executor = new NbsCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(new PlayerListener(songPlayer), this);
        this.jukeboxListener = new JukeboxListener(this);
        getServer().getPluginManager().registerEvents(jukeboxListener, this);
        jukeboxListener.start();

        // 注册孤独的音乐家管理器（低概率将怪物替换为孤独的音乐家）
        this.lonelyMusicianManager = new LonelyMusicianManager(this);
        getServer().getPluginManager().registerEvents(lonelyMusicianManager, this);
        lonelyMusicianManager.start();

        getLogger().info("NbsBook 已启用。把 .nbs 文件放入 " + nbsDirectory + " 后使用 /nbs import <文件名> [轨道]。");
        getLogger().info("孤独的音乐家功能已启用。将乐谱 .nbs 文件放入 " + musicianSongsDirectory + " 即可让音乐家携带随机乐曲。");
    }

    @Override
    public void onDisable() {
        if (lonelyMusicianManager != null) {
            lonelyMusicianManager.cleanup();
        }
        if (jukeboxListener != null) {
            jukeboxListener.cleanup();
        }
        if (songPlayer != null) {
            songPlayer.stopAll();
        }
    }

    public SongPlayer getSongPlayer() {
        return songPlayer;
    }

    public Path getNbsDirectory() {
        return nbsDirectory;
    }

    public LonelyMusicianManager getLonelyMusicianManager() {
        return lonelyMusicianManager;
    }

    public Path getMusicianSongsDirectory() {
        return musicianSongsDirectory;
    }

    /** 生成乐谱书时，在乐谱内容前预留的空白页数量。 */
    public int getBookBlankPagesBefore() {
        return Math.max(0, getConfig().getInt("book.blank_pages_before", 0));
    }
}
