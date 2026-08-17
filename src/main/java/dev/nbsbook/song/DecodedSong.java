package dev.nbsbook.song;

import java.util.List;

/**
 * 从书与笔解码出的可播放乐谱。
 *
 * @param name   曲名
 * @param author 作者
 * @param tempo  速度（tick/秒）
 * @param events 按时间顺序的刻事件列表
 */
public record DecodedSong(String name, String author, double tempo, List<TickEvent> events) {

    /**
     * 一个刻上的事件。
     *
     * @param delta 与上一个事件的刻间隔
     * @param notes 该刻上的音符
     */
    public record TickEvent(int delta, List<NoteEvent> notes) { }

    /**
     * 单个音符。
     *
     * @param key        音高，0-87（NBS 定义，0 = A0）
     * @param instrument 乐器编号，0-15
     * @param velocity   音量，0-100
     */
    public record NoteEvent(int key, int instrument, int velocity) { }
}
