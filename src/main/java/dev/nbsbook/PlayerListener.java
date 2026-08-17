package dev.nbsbook;

import dev.nbsbook.song.SongPlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 玩家退出时停止其播放任务。
 */
public final class PlayerListener implements Listener {

    private final SongPlayer songPlayer;

    public PlayerListener(SongPlayer songPlayer) {
        this.songPlayer = songPlayer;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        songPlayer.onQuit(player);
    }
}
