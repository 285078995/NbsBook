package dev.nbsbook.nbs;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析后的 NBS 乐谱。
 */
public final class NbsSong {

    /**
     * 单个音符。
     *
     * @param tick       所在刻（横向位置）
     * @param layer      所在轨道（纵向位置，0 开始）
     * @param instrument 乐器编号，0-15 为原版乐器，更高为自定义乐器
     * @param key        音高，0-87（0 = A0，87 = C8；33-57 为音符盒两个八度范围）
     * @param velocity   音量，0-100
     */
    public record Note(int tick, int layer, int instrument, int key, int velocity) { }

    private final String name;
    private final String author;
    private final double tempo;
    private final List<Note> notes;
    private final Map<Integer, String> layerNames;

    public NbsSong(String name, String author, double tempo, List<Note> notes) {
        this(name, author, tempo, notes, Collections.emptyMap());
    }

    public NbsSong(String name, String author, double tempo, List<Note> notes,
                   Map<Integer, String> layerNames) {
        this.name = name == null ? "" : name;
        this.author = author == null ? "" : author;
        this.tempo = tempo;
        this.notes = Collections.unmodifiableList(new ArrayList<>(notes));
        Map<Integer, String> copy = new LinkedHashMap<>();
        if (layerNames != null) {
            copy.putAll(layerNames);
        }
        this.layerNames = Collections.unmodifiableMap(copy);
    }

    public String getName() {
        return name;
    }

    public String getAuthor() {
        return author;
    }

    /** 速度，单位为 tick/秒。 */
    public double getTempo() {
        return tempo;
    }

    public List<Note> getNotes() {
        return notes;
    }

    public Map<Integer, String> getLayerNames() {
        return layerNames;
    }

    public String getLayerName(int layer) {
        return layerNames.get(layer);
    }

    /** 按轨道名称查找轨道编号，匹配忽略大小写并忽略首尾空格。 */
    public Integer findLayerByName(String name) {
        if (name == null) {
            return null;
        }
        String wanted = name.trim();
        for (Map.Entry<Integer, String> entry : layerNames.entrySet()) {
            if (entry.getValue() != null && entry.getValue().trim().equalsIgnoreCase(wanted)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** 返回最大的轨道号。 */
    public int maxLayer() {
        int max = 0;
        for (Note note : notes) {
            if (note.layer() > max) {
                max = note.layer();
            }
        }
        return max;
    }
}
