package dev.nbsbook.nbs;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析 .nbs（Note Block Song）文件。
 * 同时支持经典格式（原版 Minecraft Note Block Studio）与 NBS v1-v5（OpenNBS）。
 * 格式规范见 https://opennbs.org/nbs/format
 */
public final class NbsParser {

    private NbsParser() { }

    public static NbsSong parse(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        try {
            return parseBytes(bytes);
        } catch (RuntimeException e) {
            throw new IOException("NBS 文件损坏或不完整: " + e.getMessage(), e);
        }
    }

    public static NbsSong parseBytes(byte[] bytes) {
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);

        int first = buf.getShort() & 0xFFFF;
        int nbsVersion;
        if (first != 0) {
            // 经典格式：前两个字节直接是歌曲长度
            nbsVersion = 0;
        } else {
            nbsVersion = buf.get() & 0xFF;
            buf.get(); // 原版乐器数量（用于定位自定义乐器，这里用不到）
            if (nbsVersion >= 3) {
                buf.getShort(); // 歌曲长度（v3 起重新保存）
            }
        }
        int layerCount = buf.getShort() & 0xFFFF;

        String name = readString(buf);
        String author = readString(buf);
        readString(buf); // 原作者
        readString(buf); // 描述

        int tempoRaw = buf.getShort() & 0xFFFF;
        if (tempoRaw == 0) {
            throw new IllegalArgumentException("tempo 为 0");
        }
        double tempo = tempoRaw / 100.0;

        buf.get(); // 自动保存开关
        buf.get(); // 自动保存间隔
        buf.get(); // 拍号
        for (int i = 0; i < 5; i++) {
            buf.getInt(); // 统计信息：用时、点击、方块增删次数
        }
        readString(buf); // 来源 MIDI/ schematic 文件名

        if (nbsVersion >= 4) {
            buf.get();      // 循环开关
            buf.get();      // 最大循环次数
            buf.getShort(); // 循环起点
        }

        // 音符块部分：跳跃式编码
        List<NbsSong.Note> notes = new ArrayList<>();
        int tick = -1;
        while (true) {
            int jumpTick = buf.getShort() & 0xFFFF;
            if (jumpTick == 0) {
                break; // 0 表示音符部分结束
            }
            tick += jumpTick;
            int layer = -1;
            while (true) {
                int jumpLayer = buf.getShort() & 0xFFFF;
                if (jumpLayer == 0) {
                    break;
                }
                layer += jumpLayer;
                int instrument = buf.get() & 0xFF;
                int key = buf.get() & 0xFF;
                int velocity = 100;
                if (nbsVersion >= 4) {
                    velocity = buf.get() & 0xFF;
                    buf.get();      // 声像（不支持，忽略）
                    buf.getShort(); // 微调音高（音符盒无法表达，忽略）
                }
                notes.add(new NbsSong.Note(tick, layer, instrument, key, velocity));
            }
        }

        if (notes.isEmpty()) {
            throw new IllegalArgumentException("乐谱中没有音符");
        }

        // 音符区结束后是各轨道的元数据：轨道名、锁定（v4+）、音量与声像（v2+）。
        Map<Integer, String> layerNames = new LinkedHashMap<>();
        if (layerCount > 0) {
            for (int layer = 0; layer < layerCount; layer++) {
                String layerName = readString(buf);
                if (nbsVersion >= 4) {
                    buf.get(); // 锁定状态（不使用）
                }
                if (nbsVersion >= 2) {
                    buf.get(); // 音量（不使用）
                    buf.get(); // 声像（不使用）
                }
                if (layerName != null && !layerName.isBlank()) {
                    layerNames.put(layer, layerName);
                }
            }
        }

        return new NbsSong(name, author, tempo, notes, layerNames);
    }

    private static String readString(ByteBuffer buf) {
        int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IllegalArgumentException("字符串长度异常");
        }
        byte[] chars = new byte[length];
        buf.get(chars);
        String result = new String(chars, StandardCharsets.UTF_8);
        // 如果 UTF-8 解码产生替换字符，尝试 GBK 编码（部分中文工具保存的 NBS 文件使用 GBK）
        if (result.contains("\uFFFD")) {
            try {
                result = new String(chars, java.nio.charset.Charset.forName("GBK"));
            } catch (Exception ignored) {
                // GBK 不可用，保留 UTF-8 结果
            }
        }
        return result;
    }
}
