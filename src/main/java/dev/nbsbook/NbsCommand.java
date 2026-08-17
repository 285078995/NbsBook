package dev.nbsbook;

import dev.nbsbook.nbs.NbsParser;
import dev.nbsbook.nbs.NbsSong;
import dev.nbsbook.song.BookUtil;
import dev.nbsbook.song.DecodedSong;
import dev.nbsbook.song.SongCodec;
import dev.nbsbook.song.SongPlayer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * /nbs 命令处理。
 *
 * <ul>
 *   <li>/nbs import &lt;文件名&gt; [轨道名称] —— 需要 nbsbook.use 权限</li>
 *   <li>/nbs load &lt;文件名&gt; [轨道名称] —— 需要 nbsbook.admin 权限，可从 nbs 或 musician_songs 目录加载</li>
 *   <li>/nbs list —— 需要 nbsbook.admin 权限（默认 OP）</li>
 *   <li>/nbs play —— 需要 nbsbook.play 权限（默认所有人）</li>
 *   <li>/nbs stop —— 需要 nbsbook.play 权限（默认所有人）</li>
 *   <li>/nbs musician &lt;on|off|status|stop&gt; —— 需要 nbsbook.admin 权限（默认 OP）</li>
 *   <li>/nbs jukebox stop —— 需要 nbsbook.admin 权限（默认 OP）</li>
 * </ul>
 */
public final class NbsCommand implements TabExecutor {

    private final NbsBookPlugin plugin;

    public NbsCommand(NbsBookPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "import" -> {
                if (!sender.hasPermission("nbsbook.use")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.use）。", NamedTextColor.RED));
                    return true;
                }
                handleImport(sender, args);
            }
            case "load" -> {
                if (!sender.hasPermission("nbsbook.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.admin）。", NamedTextColor.RED));
                    return true;
                }
                handleLoadBoth(sender, args);
            }
            case "list" -> {
                if (!sender.hasPermission("nbsbook.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.admin）。", NamedTextColor.RED));
                    return true;
                }
                handleList(sender);
            }
            case "play" -> {
                if (!sender.hasPermission("nbsbook.play")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.play）。", NamedTextColor.RED));
                    return true;
                }
                handlePlay(sender);
            }
            case "stop" -> {
                if (!sender.hasPermission("nbsbook.play")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.play）。", NamedTextColor.RED));
                    return true;
                }
                handleStop(sender);
            }
            case "summon" -> {
                if (!sender.hasPermission("nbsbook.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.admin）。", NamedTextColor.RED));
                    return true;
                }
                handleSummon(sender, args);
            }
            case "musician" -> {
                if (!sender.hasPermission("nbsbook.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.admin）。", NamedTextColor.RED));
                    return true;
                }
                handleMusicianToggle(sender, args);
            }
            case "jukebox" -> {
                if (!sender.hasPermission("nbsbook.admin")) {
                    sender.sendMessage(Component.text("你没有权限使用该命令（需要 nbsbook.admin）。", NamedTextColor.RED));
                    return true;
                }
                handleJukeboxControl(sender, args);
            }
            default -> sendHelp(sender);
        }
        return true;
    }

    /** 列出 nbs 目录下的所有文件，方便核对。 */
    private void handleList(CommandSender sender) {
        Path dir = plugin.getNbsDirectory();
        sender.sendMessage(Component.text("nbs 目录: ", NamedTextColor.GOLD)
                .append(Component.text(dir.toString(), NamedTextColor.YELLOW)));
        List<String> files = listNbsFiles(dir);
        if (files.isEmpty()) {
            sender.sendMessage(Component.text("目录为空——请把 .nbs 文件放进上面的目录里。", NamedTextColor.RED));
            return;
        }
        for (String name : files) {
            sender.sendMessage(Component.text("- " + name, NamedTextColor.GRAY));
        }
        sender.sendMessage(Component.text("共 " + files.size() + " 个文件，导入示例: /nbs import " + files.get(0), NamedTextColor.DARK_GRAY));
    }

    private static List<String> listNbsFiles(Path dir) {
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            return List.of();
        }
    }

    /**
     * 在 nbs 目录内查找文件：先精确匹配，再补 .nbs 后缀匹配，最后忽略大小写匹配。
     * 只允许目录内的文件名，防止路径穿越。
     */
    private Path findNbsFile(String requested) {
        if (requested.contains("/") || requested.contains("\\") || requested.contains("..")) {
            return null;
        }
        Path dir = plugin.getNbsDirectory();

        // 1. 精确匹配
        Path exact = dir.resolve(requested);
        if (Files.isRegularFile(exact)) {
            return exact;
        }

        // 2. 如果没有 .nbs 后缀，自动补上
        if (!requested.toLowerCase().endsWith(".nbs")) {
            Path withExt = dir.resolve(requested + ".nbs");
            if (Files.isRegularFile(withExt)) {
                return withExt;
            }
        }

        // 3. 忽略大小写匹配（Linux 服务器文件系统区分大小写）
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

        // 4. 补 .nbs 后缀 + 忽略大小写
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

    /** 依次在 nbs 目录和 musician_songs 目录中查找乐谱文件。 */
    private Path findNbsOrMusicianFile(String requested) {
        Path inNbs = findNbsFile(requested);
        if (inNbs != null) {
            return inNbs;
        }
        return plugin.getLonelyMusicianManager().resolveMusicianSong(requested);
    }

    private void handleImport(CommandSender sender, String[] args) {
        handleLoad(sender, args, "import", false);
    }

    private void handleLoadBoth(CommandSender sender, String[] args) {
        handleLoad(sender, args, "load", true);
    }

    private void handleLoad(CommandSender sender, String[] args, String commandName,
                            boolean includeMusicianSongs) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /nbs " + commandName
                    + " <文件名> [轨道名称]，例如 /nbs " + commandName + " song.nbs 主旋律", NamedTextColor.YELLOW));
            return;
        }

        // 文件名可能含空格：把剩余参数从长到短依次拼成文件名尝试查找，
        // 剩下恰好一个参数时视为轨道过滤参数。
        Path file = null;
        int nameTokens = 0;
        int total = args.length - 1;
        for (int n = total; n >= 1; n--) {
            String name = String.join(" ", Arrays.asList(args).subList(1, 1 + n));
            Path found = includeMusicianSongs ? findNbsOrMusicianFile(name) : findNbsFile(name);
            if (found == null) {
                continue;
            }
            int remaining = total - n;
            if (remaining > 1) {
                continue; // 剩余参数过多，不是合法组合，继续尝试更短的文件名
            }
            file = found;
            nameTokens = n;
            break;
        }

        if (file == null) {
            if (includeMusicianSongs) {
                List<String> nbsFiles = listNbsFiles(plugin.getNbsDirectory());
                List<String> musicianFiles = plugin.getLonelyMusicianManager().listMusicianSongs().stream()
                        .map(p -> p.getFileName().toString())
                        .toList();
                if (nbsFiles.isEmpty() && musicianFiles.isEmpty()) {
                    sender.sendMessage(Component.text("nbs 和 musician_songs 目录当前都为空，请先放入 .nbs 乐谱文件。", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("在 nbs 和 musician_songs 目录中找不到文件: " + args[1], NamedTextColor.RED));
                    sender.sendMessage(Component.text("nbs 目录:", NamedTextColor.YELLOW));
                    for (String name : nbsFiles) {
                        sender.sendMessage(Component.text("- " + name, NamedTextColor.GRAY));
                    }
                    sender.sendMessage(Component.text("musician_songs 目录:", NamedTextColor.YELLOW));
                    for (String name : musicianFiles) {
                        sender.sendMessage(Component.text("- " + name, NamedTextColor.GRAY));
                    }
                }
                return;
            } else {
                List<String> existing = listNbsFiles(plugin.getNbsDirectory());
                sender.sendMessage(Component.text("在 " + plugin.getNbsDirectory() + " 中找不到文件: " + args[1], NamedTextColor.RED));
                if (existing.isEmpty()) {
                    sender.sendMessage(Component.text("该目录当前为空。请确认 .nbs 文件已放入此目录（不是 plugins/NbsBook 本身），"
                            + "Windows 下请检查文件实际扩展名是 .nbs 而非 .nbs.nbs / .nbs.txt。", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("目录中现有文件:", NamedTextColor.YELLOW));
                    for (String name : existing) {
                        sender.sendMessage(Component.text("- " + name, NamedTextColor.GRAY));
                    }
                    sender.sendMessage(Component.text("请使用 /nbs list 查看，或直接输入上面列出的文件名。", NamedTextColor.YELLOW));
                }
                return;
            }
        }

        NbsSong song;
        try {
            song = NbsParser.parse(file);
        } catch (IOException | RuntimeException e) {
            sender.sendMessage(Component.text("解析失败: " + e.getMessage(), NamedTextColor.RED));
            return;
        }

        // 轨道过滤（文件名后面的那个参数才是轨道名称；不填则导入全部轨道）
        String trackArg = (total - nameTokens == 1) ? args[1 + nameTokens] : null;
        NbsSong filtered;
        if (trackArg == null) {
            filtered = song;
        } else {
            Integer layer = song.findLayerByName(trackArg);
            if (layer == null) {
                String available = song.getLayerNames().isEmpty()
                        ? "该乐谱没有轨道名称"
                        : String.join(", ", song.getLayerNames().values());
                sender.sendMessage(Component.text("找不到轨道: " + trackArg, NamedTextColor.RED));
                sender.sendMessage(Component.text("可用轨道: " + available, NamedTextColor.GRAY));
                return;
            }
            filtered = filterLayers(song, List.of(layer));
        }
        if (filtered.getNotes().isEmpty()) {
            sender.sendMessage(Component.text("所选轨道中没有音符。", NamedTextColor.RED));
            return;
        }

        List<String> pages;
        try {
            pages = SongCodec.encode(filtered);
        } catch (SongCodec.CodecException e) {
            sender.sendMessage(Component.text("编码失败: " + e.getMessage(), NamedTextColor.RED));
            return;
        }

        List<String> bookPages;
        try {
            bookPages = BookUtil.addLeadingBlankPages(pages, plugin.getBookBlankPagesBefore());
        } catch (IllegalArgumentException e) {
            sender.sendMessage(Component.text("无法生成乐谱书: " + e.getMessage(), NamedTextColor.RED));
            return;
        }

        // 乐谱名称：优先用 NBS 内部名称（已修复中文编码），其次用文件名
        String songName = filtered.getName().isEmpty()
                ? stripExtension(file.getFileName().toString())
                : filtered.getName();

        ItemStack book = BookUtil.writeSongBook(
                new DecodedSong(songName, song.getAuthor(), song.getTempo(), List.of()),
                bookPages, songName);

        if (player.getInventory().firstEmpty() == -1) {
            sender.sendMessage(Component.text("背包已满，无法给予乐谱书。", NamedTextColor.RED));
            return;
        }
        player.getInventory().addItem(book);
        sender.sendMessage(Component.text("已生成乐谱书: ", NamedTextColor.GREEN)
                .append(Component.text(songName, NamedTextColor.YELLOW))
                .append(Component.text("（音符 " + filtered.getNotes().size() + " 个，" + pages.size() + " 页）", NamedTextColor.GRAY)));
    }

    private void handlePlay(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        ItemStack mainHand = player.getInventory().getItemInMainHand();
        if (!BookUtil.isWritableBook(mainHand)) {
            sender.sendMessage(Component.text("请先在手中拿着书与笔。", NamedTextColor.RED));
            return;
        }
        List<String> pages = BookUtil.readPages(mainHand);
        if (pages == null || pages.isEmpty()) {
            sender.sendMessage(Component.text("这本书是空的。", NamedTextColor.RED));
            return;
        }

        DecodedSong song;
        try {
            song = SongCodec.decode(pages);
        } catch (SongCodec.CodecException e) {
            sender.sendMessage(Component.text("乐谱解析失败: " + e.getMessage(), NamedTextColor.RED));
            return;
        }

        // 从 lore 读回歌曲名（避免页面文本序列化丢失中文字符）
        String loreName = BookUtil.readNameFromLore(mainHand);
        DecodedSong finalSong = (!loreName.isEmpty() && song.name().isEmpty())
                ? new DecodedSong(loreName, song.author(), song.tempo(), song.events())
                : song;

        SongPlayer songPlayer = plugin.getSongPlayer();
        songPlayer.play(player, finalSong);
    }

    private void handleStop(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        if (plugin.getSongPlayer().stopPlayerPlayback(player)) {
            sender.sendMessage(Component.text("已停止玩家播放。", NamedTextColor.GRAY));
        } else {
            sender.sendMessage(Component.text("当前没有由 /nbs play 播放的乐谱。", NamedTextColor.GRAY));
        }
    }

    private void handleSummon(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }

        LonelyMusicianManager manager = plugin.getLonelyMusicianManager();

        String modelArg = args.length >= 2 ? args[1] : null;
        String songArg = args.length >= 3 ? String.join(" ", Arrays.asList(args).subList(2, args.length)) : null;

        EntityType type;
        if (modelArg != null && !modelArg.isBlank()) {
            type = manager.resolveMusicianType(modelArg);
            if (type == null) {
                sender.sendMessage(Component.text("未知的模型类型: " + modelArg, NamedTextColor.RED));
                sender.sendMessage(Component.text("可用模型: " + String.join(", ", manager.listMusicianTypeNames()), NamedTextColor.GRAY));
                return;
            }
        } else {
            type = manager.randomMusicianType();
        }

        Path songFile;
        if (songArg != null && !songArg.isBlank()) {
            songFile = manager.resolveMusicianSong(songArg);
            if (songFile == null) {
                sender.sendMessage(Component.text("在 " + plugin.getMusicianSongsDirectory() + " 中找不到乐谱: " + songArg, NamedTextColor.RED));
                List<Path> available = manager.listMusicianSongs();
                if (available.isEmpty()) {
                    sender.sendMessage(Component.text("该目录当前为空，请先放入 .nbs 乐谱文件。", NamedTextColor.RED));
                } else {
                    sender.sendMessage(Component.text("目录中现有乐谱:", NamedTextColor.YELLOW));
                    for (Path path : available) {
                        sender.sendMessage(Component.text("- " + path.getFileName(), NamedTextColor.GRAY));
                    }
                }
                return;
            }
        } else {
            songFile = manager.randomMusicianSong();
            if (songFile == null) {
                sender.sendMessage(Component.text("musician_songs 目录为空，请先放入 .nbs 乐谱文件。", NamedTextColor.RED));
                return;
            }
        }

        String error = manager.summonMusician(player.getLocation(), type, songFile);
        if (error != null) {
            sender.sendMessage(Component.text("召唤失败: " + error, NamedTextColor.RED));
        } else {
            sender.sendMessage(Component.text("♪ 孤独的音乐家已在你身边出现！", NamedTextColor.LIGHT_PURPLE));
        }
    }

    private void handleMusicianToggle(CommandSender sender, String[] args) {
        LonelyMusicianManager manager = plugin.getLonelyMusicianManager();
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /nbs musician <on|off|status|stop>", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("当前状态: " + (manager.isEnabled() ? "开启" : "关闭"), NamedTextColor.GRAY));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "on", "enable", "true" -> {
                manager.setEnabled(true);
                sender.sendMessage(Component.text("音乐家自动生成已开启。", NamedTextColor.GREEN));
            }
            case "off", "disable", "false" -> {
                manager.setEnabled(false);
                sender.sendMessage(Component.text("音乐家自动生成已关闭。", NamedTextColor.GRAY));
            }
            case "status" -> {
                sender.sendMessage(Component.text("音乐家自动生成状态: " + (manager.isEnabled() ? "开启" : "关闭"), NamedTextColor.GOLD));
            }
            case "stop" -> {
                if (sender instanceof Player player) {
                    if (manager.stopPlaybackFor(player)) {
                        sender.sendMessage(Component.text("已停止音乐家演奏。", NamedTextColor.GRAY));
                    } else {
                        sender.sendMessage(Component.text("当前没有音乐家正在为你演奏。", NamedTextColor.GRAY));
                    }
                } else {
                    sender.sendMessage(Component.text("该子命令只能由玩家执行。", NamedTextColor.RED));
                }
            }
            default -> sender.sendMessage(Component.text("未知参数: " + args[1] + "（可用: on/off/status/stop）", NamedTextColor.RED));
        }
    }

    private void handleJukeboxControl(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("该命令只能由玩家执行。", NamedTextColor.RED));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Component.text("用法: /nbs jukebox stop", NamedTextColor.YELLOW));
            return;
        }
        if (args[1].equalsIgnoreCase("stop")) {
            if (plugin.getSongPlayer().stopJukeboxPlayback(player)) {
                sender.sendMessage(Component.text("已停止唱片机播放。乐谱仍留在唱片机中，右键唱片机可弹出。", NamedTextColor.GRAY));
            } else {
                sender.sendMessage(Component.text("当前没有由唱片机播放的乐谱。", NamedTextColor.GRAY));
            }
        } else {
            sender.sendMessage(Component.text("未知参数: " + args[1] + "（可用: stop）", NamedTextColor.RED));
        }
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Component.text("=== NbsBook 帮助 ===", NamedTextColor.GOLD));
        if (sender.hasPermission("nbsbook.use")) {
            sender.sendMessage(Component.text("/nbs import <文件名> [轨道名称] - 从 nbs 目录导入乐谱为书本", NamedTextColor.YELLOW));
        }
        if (sender.hasPermission("nbsbook.admin")) {
            sender.sendMessage(Component.text("/nbs load <文件名> [轨道名称] - 从 nbs 或 musician_songs 目录导入乐谱", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/nbs list - 查看 nbs 目录及其中的文件", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/nbs summon [模型] [乐谱] - 在当前位置召唤孤独的音乐家", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/nbs musician <on|off|status|stop> - 开关音乐家自动生成或停止音乐家演奏", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/nbs jukebox stop - 停止唱片机播放", NamedTextColor.YELLOW));
        }
        if (sender.hasPermission("nbsbook.play")) {
            sender.sendMessage(Component.text("/nbs play - 播放手中书本里的乐谱", NamedTextColor.YELLOW));
            sender.sendMessage(Component.text("/nbs stop - 停止 /nbs play 发起的播放", NamedTextColor.YELLOW));
        }
    }

    private static NbsSong filterLayers(NbsSong song, List<Integer> layerFilter) {
        if (layerFilter == null) {
            return song;
        }
        List<NbsSong.Note> kept = new ArrayList<>();
        for (NbsSong.Note note : song.getNotes()) {
            if (layerFilter.contains(note.layer())) {
                kept.add(note);
            }
        }
        return new NbsSong(song.getName(), song.getAuthor(), song.getTempo(), kept);
    }

    /** 去掉文件名末尾的 .nbs 后缀，用作乐谱显示名称。 */
    private static String stripExtension(String fileName) {
        if (fileName.toLowerCase().endsWith(".nbs")) {
            return fileName.substring(0, fileName.length() - 4);
        }
        return fileName;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> cmds = new ArrayList<>();
            if (sender.hasPermission("nbsbook.use")) {
                cmds.add("import");
            }
            if (sender.hasPermission("nbsbook.admin")) {
                cmds.add("load");
                cmds.add("list");
                cmds.add("summon");
                cmds.add("musician");
                cmds.add("jukebox");
            }
            if (sender.hasPermission("nbsbook.play")) {
                cmds.add("play");
                cmds.add("stop");
            }
            return filterByPrefix(cmds, args[0]);
        }
        boolean importCompletion = args[0].equalsIgnoreCase("import") && sender.hasPermission("nbsbook.use");
        boolean loadCompletion = args[0].equalsIgnoreCase("load") && sender.hasPermission("nbsbook.admin");
        if (args.length >= 2 && (importCompletion || loadCompletion)) {
            // 对 import/load 子命令的文件名参数补全：支持带空格的文件名
            String joined = String.join(" ", Arrays.asList(args).subList(1, args.length));
            Path dir = plugin.getNbsDirectory();
            try (Stream<Path> stream = Files.list(dir)) {
                List<String> names = new ArrayList<>(stream
                        .filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                        .map(p -> p.getFileName().toString())
                        .sorted()
                        .toList());
                if (args[0].equalsIgnoreCase("load")) {
                    for (Path musicianSong : plugin.getLonelyMusicianManager().listMusicianSongs()) {
                        names.add(musicianSong.getFileName().toString());
                    }
                }
                // 找到最后一个空格前的部分作为已完成的前缀，对最后一段做补全
                int lastSpace = joined.lastIndexOf(' ');
                String prefix = lastSpace >= 0 ? joined.substring(0, lastSpace + 1) : "";
                String lastToken = lastSpace >= 0 ? joined.substring(lastSpace + 1) : joined;

                List<String> result = new ArrayList<>();
                String lower = lastToken.toLowerCase();
                for (String name : names) {
                    // 用完整文件名（去掉已确认前缀后）与当前输入段比较
                    String remaining = name;
                    if (!prefix.isEmpty() && name.startsWith(prefix)) {
                        remaining = name.substring(prefix.length());
                    } else if (!prefix.isEmpty()) {
                        continue;
                    }
                    if (remaining.toLowerCase().startsWith(lower)) {
                        result.add(prefix + remaining);
                    }
                }
                return result;
            } catch (IOException e) {
                return List.of();
            }
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("musician") && sender.hasPermission("nbsbook.admin")) {
            if (args.length == 2) {
                return filterByPrefix(List.of("on", "off", "status", "stop"), args[1]);
            }
            return List.of();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("jukebox") && sender.hasPermission("nbsbook.admin")) {
            if (args.length == 2) {
                return filterByPrefix(List.of("stop"), args[1]);
            }
            return List.of();
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("summon") && sender.hasPermission("nbsbook.admin")) {
            if (args.length == 2) {
                return filterByPrefix(plugin.getLonelyMusicianManager().listMusicianTypeNames(), args[1]);
            }
            String joined = String.join(" ", Arrays.asList(args).subList(2, args.length));
            return completeMusicianSong(joined);
        }
        return List.of();
    }

    private List<String> completeMusicianSong(String joined) {
        Path dir = plugin.getMusicianSongsDirectory();
        try (Stream<Path> stream = Files.list(dir)) {
            List<String> names = stream
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().toLowerCase().endsWith(".nbs"))
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
            int lastSpace = joined.lastIndexOf(' ');
            String prefix = lastSpace >= 0 ? joined.substring(0, lastSpace + 1) : "";
            String lastToken = lastSpace >= 0 ? joined.substring(lastSpace + 1) : joined;
            List<String> result = new ArrayList<>();
            String lower = lastToken.toLowerCase();
            for (String name : names) {
                String remaining = name;
                if (!prefix.isEmpty() && name.startsWith(prefix)) {
                    remaining = name.substring(prefix.length());
                } else if (!prefix.isEmpty()) {
                    continue;
                }
                if (remaining.toLowerCase().startsWith(lower)) {
                    result.add(prefix + remaining);
                }
            }
            return result;
        } catch (IOException e) {
            return List.of();
        }
    }

    private static List<String> filterByPrefix(List<String> options, String prefix) {
        List<String> result = new ArrayList<>();
        String lower = prefix.toLowerCase();
        for (String option : options) {
            if (option.toLowerCase().startsWith(lower)) {
                result.add(option);
            }
        }
        return result;
    }
}
