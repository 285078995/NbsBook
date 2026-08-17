package dev.nbsbook.song;

import dev.nbsbook.nbs.NbsSong;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 乐谱文本编解码器：NBS 乐谱 → 书本文本，以及反向解码。
 *
 * <p>文本格式：</p>
 * <pre>
 * 第 1 行  #NBSBOOK|1|tempo=10.0|name=曲名|author=作者|ticks=总刻数
 * 之后     tick:i乐器,k音高,p间隔;...     （同一刻的音符用 ; 分隔）
 * </pre>
 *
 * <p>字段含义：i = 乐器编号（0-15），k = 音高（0-87，NBS 定义），
 * p = 距上一个有音符的刻的间隔（刻，起点为 -1，与 NBS 跳跃编码语义一致），
 * v = 音量（0-100，省略时为 100）。
 * 同一行内所有音符共享同一刻，p 取首个音符的值。</p>
 */
public final class SongCodec {

    /** 书本页数上限（书与笔游戏内上限为 100 页）。 */
    public static final int MAX_PAGES = 100;
    /** 单页字符上限（游戏内为 1024，留出余量）。 */
    public static final int PAGE_LIMIT = 1000;
    /** 解码时允许的最大刻数，防止异常数据占用过多资源。 */
    public static final int MAX_TICKS = 2_000_000;

    private static final int FORMAT_VERSION = 1;

    /** 匹配一个刻上的音符行：单音符或 ; 分隔的和弦。 */
    private static final Pattern TICK_LINE = Pattern.compile(
            "^tick:((?:i\\d+,k\\d+,p\\d+(?:,v\\d+)?)(?:;i\\d+,k\\d+,p\\d+(?:,v\\d+)?)*)$");
    private static final Pattern NOTE_PART = Pattern.compile(
            "i(\\d+),k(\\d+),p(\\d+)(?:,v(\\d+))?");

    private SongCodec() { }

    /** 编码格式异常。 */
    public static final class CodecException extends RuntimeException {
        public CodecException(String message) {
            super(message);
        }
    }

    /**
     * 将 NBS 乐谱编码为书页列表。
     *
     * @throws CodecException 乐谱超出书本容量时抛出
     */
    public static List<String> encode(NbsSong song) {
        List<NbsSong.Note> notes = new ArrayList<>(song.getNotes());
        notes.sort(Comparator.comparingInt(NbsSong.Note::tick));

        StringBuilder out = new StringBuilder();
        int totalTicks = notes.get(notes.size() - 1).tick() + 1;
        out.append("#NBSBOOK|").append(FORMAT_VERSION)
                .append("|tempo=").append(trim(song.getTempo()))
                .append("|name=").append(sanitize(song.getName()))
                .append("|author=").append(sanitize(song.getAuthor()))
                .append("|ticks=").append(totalTicks).append('\n');

        // 音符行：按刻分组，每行 = 一个刻上的所有音符
        // p = 距上一个有音符的刻的间隔（起点为 -1，与 NBS 跳跃编码语义一致）
        int index = 0;
        int size = notes.size();
        int prevTick = -1;
        while (index < size) {
            int tick = notes.get(index).tick();
            int groupEnd = index + 1;
            while (groupEnd < size && notes.get(groupEnd).tick() == tick) {
                groupEnd++;
            }
            int delta = tick - prevTick;

            StringBuilder line = new StringBuilder("tick:");
            for (int i = index; i < groupEnd; i++) {
                if (i > index) {
                    line.append(';');
                }
                NbsSong.Note note = notes.get(i);
                line.append('i').append(note.instrument())
                        .append(",k").append(note.key())
                        .append(",p").append(delta);
                if (note.velocity() != 100) {
                    line.append(",v").append(note.velocity());
                }
            }
            out.append(line).append('\n');
            prevTick = tick;
            index = groupEnd;
        }

        return paginate(out.toString());
    }

    private static List<String> paginate(String text) {
        List<String> pages = new ArrayList<>();
        StringBuilder page = new StringBuilder();
        for (String line : text.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            if (line.length() > PAGE_LIMIT) {
                throw new CodecException("单行数据过长，乐谱过大无法写入书本");
            }
            if (page.length() + line.length() + 1 > PAGE_LIMIT) {
                pages.add(page.toString());
                page.setLength(0);
                if (pages.size() >= MAX_PAGES) {
                    throw new CodecException("乐谱过大：超过书本 " + MAX_PAGES + " 页的容量上限，无法写入");
                }
            }
            if (page.length() > 0) {
                page.append('\n');
            }
            page.append(line);
        }
        if (page.length() > 0) {
            pages.add(page.toString());
        }
        if (pages.isEmpty()) {
            throw new CodecException("编码结果为空");
        }
        return pages;
    }

    /**
     * 从书页文本解码乐谱。
     *
     * @throws CodecException 格式非法时抛出
     */
    public static DecodedSong decode(List<String> pages) {
        List<String> lines = new ArrayList<>();
        for (String page : pages) {
            if (page == null) {
                continue;
            }
            for (String line : page.replace("\r", "").split("\n", -1)) {
                if (!line.isBlank()) {
                    lines.add(line.trim());
                }
            }
        }
        if (lines.isEmpty()) {
            throw new CodecException("书本内容为空");
        }

        // 第 1 行：文件头
        String header = lines.get(0);
        if (!header.startsWith("#NBSBOOK|")) {
            throw new CodecException("不是 NBSBOOK 乐谱：缺少文件头（第 1 行应为 #NBSBOOK|...）");
        }
        double tempo = 10.0;
        String name = "";
        String author = "";
        for (String part : header.split("\\|")) {
            int eq = part.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String key = part.substring(0, eq);
            String value = part.substring(eq + 1);
            switch (key) {
                case "tempo" -> {
                    try {
                        tempo = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        throw new CodecException("tempo 数值非法: " + value);
                    }
                }
                case "name" -> name = value;
                case "author" -> author = value;
                default -> { }
            }
        }
        if (!(tempo >= 0.01 && tempo <= 1000.0)) {
            throw new CodecException("tempo 超出有效范围 (0.01-1000): " + tempo);
        }

        // 音符行：同一行 = 同一刻上的和弦
        List<DecodedSong.TickEvent> events = new ArrayList<>();
        int lastTick = -1;
        for (int i = 1; i < lines.size(); i++) {
            Matcher matcher = TICK_LINE.matcher(lines.get(i));
            if (!matcher.matches()) {
                throw new CodecException("第 " + (i + 1) + " 行格式非法: " + lines.get(i));
            }

            List<DecodedSong.NoteEvent> chord = new ArrayList<>();
            int delta = -1;
            Matcher noteMatcher = NOTE_PART.matcher(matcher.group(1));
            while (noteMatcher.find()) {
                int instrument = Integer.parseInt(noteMatcher.group(1));
                int key = Integer.parseInt(noteMatcher.group(2));
                int noteDelta = Integer.parseInt(noteMatcher.group(3));
                int velocity = noteMatcher.group(4) == null ? 100 : Integer.parseInt(noteMatcher.group(4));
                if (instrument > 15) {
                    throw new CodecException("乐器编号超出范围 (0-15): " + instrument);
                }
                if (key > 87) {
                    throw new CodecException("音高超出范围 (0-87): " + key);
                }
                if (velocity > 100) {
                    throw new CodecException("音量超出范围 (0-100): " + velocity);
                }
                if (noteDelta <= 0) {
                    throw new CodecException("间隔必须大于 0");
                }
                if (delta < 0) {
                    delta = noteDelta; // 首个音符决定该刻到下一刻的间隔
                }
                chord.add(new DecodedSong.NoteEvent(key, instrument, velocity));
            }

            lastTick += delta;
            if (lastTick > MAX_TICKS) {
                throw new CodecException("乐谱过长，超出允许的刻数上限");
            }
            events.add(new DecodedSong.TickEvent(delta, List.copyOf(chord)));
        }
        if (events.isEmpty()) {
            throw new CodecException("书本中没有音符数据");
        }
        return new DecodedSong(name, author, tempo, List.copyOf(events));
    }

    /** 去掉文本中会破坏格式的字符。 */
    private static String sanitize(String text) {
        return text.replace('\n', ' ').replace('\r', ' ').replace('|', ' ').replace('=', '_');
    }

    private static String trim(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
