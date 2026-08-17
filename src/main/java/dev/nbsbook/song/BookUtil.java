package dev.nbsbook.song;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.WritableBookMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 书与笔（WRITABLE_BOOK）读写工具。
 */
public final class BookUtil {

    private BookUtil() { }

    /**
     * 判断物品是否是书与笔。
     */
    public static boolean isWritableBook(ItemStack item) {
        return item != null && item.getType() == Material.WRITABLE_BOOK;
    }

    /**
     * 读取出书本中所有页的文本内容。
     *
     * @return 每页一个字符串；物品不是书与笔或没有元数据时返回 null
     */
    public static List<String> readPages(ItemStack item) {
        if (!isWritableBook(item) || !item.hasItemMeta()) {
            return null;
        }
        WritableBookMeta meta = (WritableBookMeta) item.getItemMeta();
        if (!meta.hasPages()) {
            return null;
        }
        return new ArrayList<>(meta.getPages());
    }

    /**
     * 在乐谱页面之前插入指定数量的空白页。
     *
     * @param pages      乐谱页面
     * @param blankPages 前置空白页数量
     * @return 插入空白页后的页面列表；blankPages 为 0 时原样返回
     */
    public static List<String> addLeadingBlankPages(List<String> pages, int blankPages) {
        if (blankPages < 0) {
            throw new IllegalArgumentException("前置空白页数量不能为负数");
        }
        if (blankPages == 0) {
            return pages;
        }
        if (pages.size() + blankPages > SongCodec.MAX_PAGES) {
            throw new IllegalArgumentException("前置空白页过多：乐谱需要 " + pages.size()
                    + " 页，最多还能添加 " + (SongCodec.MAX_PAGES - pages.size()) + " 页空白");
        }
        List<String> result = new ArrayList<>(blankPages + pages.size());
        for (int i = 0; i < blankPages; i++) {
            result.add("");
        }
        result.addAll(pages);
        return result;
    }

    /** 书本 lore 中隐藏的歌曲名标记前缀 */
    private static final String NAME_LORE_PREFIX = "§0NBSBook§r ";

    /**
     * 把编码后的乐谱页面写入一本新的书与笔。
     *
     * @param song         乐谱（用于设置说明信息）
     * @param pages        编码后的页面文本
     * @param nameOverride 歌曲显示名（写入 lore 供 play 时精确读回，避免页面序列化丢中文）
     */
    public static ItemStack writeSongBook(DecodedSong song, List<String> pages, String nameOverride) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        WritableBookMeta meta = (WritableBookMeta) book.getItemMeta();

        meta.setPages(pages);

        String title = nameOverride != null && !nameOverride.isEmpty()
                ? nameOverride
                : (song.name().isEmpty() ? "未命名乐谱" : song.name());
        meta.displayName(Component.text("乐谱: " + title, NamedTextColor.GOLD)
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(Component.text("作者: " + (song.author().isEmpty() ? "未知" : song.author()), NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("速度: " + song.tempo() + " tick/s", NamedTextColor.GRAY)
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.text("使用 /nbs play 播放", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.ITALIC, false));
        // 隐藏的歌曲名标记：play 时从此处精确读回，避免页面文本序列化丢失中文字符
        lore.add(Component.text(NAME_LORE_PREFIX + title).color(NamedTextColor.BLACK)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);

        book.setItemMeta(meta);
        return book;
    }

    /**
     * 从书与笔的 lore 中读取歌曲名。
     *
     * @return 歌曲名；如果 lore 中没有标记则返回空字符串
     */
    public static String readNameFromLore(ItemStack item) {
        if (item == null || !item.hasItemMeta() || !item.getItemMeta().hasLore()) {
            return "";
        }
        for (Component line : item.getItemMeta().lore()) {
            String plain = PlainTextComponentSerializer.plainText().serialize(line);
            if (plain.startsWith(NAME_LORE_PREFIX)) {
                return plain.substring(NAME_LORE_PREFIX.length());
            }
        }
        return "";
    }
}
