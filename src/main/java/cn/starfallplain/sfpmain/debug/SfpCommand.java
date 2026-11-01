package cn.starfallplain.sfpmain.debug;

import cn.starfallplain.sfpmain.SfpMain;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code /sfp} —— 星落平原管理 / 调试命令。
 * <p>
 * 权限：默认要求 {@code sfpmenu.admin}；{@code /sfp sweep} 只要求
 * {@code sfpmenu.clean.manage}；{@code /sfp player|fp} 转交假人命令，要求 {@code sfpmenu.bot}。
 * 未知子命令会提示「未知子命令：xxx」+ 最接近的写法 + 用法入口。
 *
 * <pre>
 *   /sfp reload                 重载配置（开关类改动仍需重启服务器）
 *   /sfp status                 运行状态总览：模块开关 + 各模块实时数据
 *   /sfp db                     数据库概览：文件、版本、journal、各表行数
 *   /sfp db tables              表结构：每张表的列名与行数
 *   /sfp db homes [玩家名]      某个玩家的家（默认自己），并标出世界是否存在
 *   /sfp db warps               全部公共传送点
 *   /sfp db back [玩家名]       某个玩家的 /back 记录
 *   /sfp db check               PRAGMA integrity_check / foreign_key_check
 *   /sfp db checkpoint          把 WAL 合并回主库（便于直接拷贝 .db 文件）
 *   /sfp test                   全量自检：配置、菜单、数据层、传送内容、功能模块
 *   /sfp sweep on|off           开启 / 关闭自动扫地（需 sfpmenu.clean.manage）
 *   /sfp sweep except add|remove &lt;世界&gt; &lt;区块X&gt; &lt;区块Z&gt;  维护扫地例外区块
 *   /sfp player|fp &lt;子命令&gt;      假人管理（需 sfpmenu.bot，用法见 /sfp player help）
 * </pre>
 *
 * 子命令的输出文案写死在本包内：诊断条目多且高度动态（表名、行数、列名、失效条目……），
 * 塞进 messages.yml 既难读也难维护。玩家可见的常规文案仍然走 messages.yml。
 */
public final class SfpCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS =
            List.of("reload", "status", "db", "test", "sweep", "player", "fp");
    private static final List<String> DB_SUBCOMMANDS =
            List.of("tables", "homes", "warps", "back", "check", "checkpoint");

    private final SfpMain plugin;

    public SfpCommand(SfpMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();

        // 假人命令：/sfp player <子命令>（简写 /sfp fp）。
        // 它有自己的权限（sfpmenu.bot），与 /sfp 的管理权限（sfpmenu.admin）相互独立，
        // 所以必须在管理权限检查之前转交。
        if (args.length > 0 && (args[0].equalsIgnoreCase("player") || args[0].equalsIgnoreCase("fp"))) {
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            new cn.starfallplain.sfpmain.bot.BotCommand(plugin).execute(source, rest);
            return;
        }

        if (args.length > 0 && args[0].equalsIgnoreCase("sweep")) {
            if (!sender.hasPermission("sfpmenu.clean.manage")) {
                noPermission(sender, "sfpmenu.clean.manage");
                return;
            }
            sweep(sender, args);
            return;
        }

        if (!sender.hasPermission("sfpmenu.admin")) {
            noPermission(sender, "sfpmenu.admin");
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "reload" -> reload(sender);
            case "status" -> status(sender);
            case "db" -> db(sender, args);
            case "test" -> SelfTest.run(plugin, sender);
            default -> unknownSubcommand(sender, args[0]);
        }
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        // /sfp player|fp <子命令> → 转交假人命令补全（权限检查前也能补全，与 execute 一致）
        if (args.length > 0 && (args[0].equalsIgnoreCase("player") || args[0].equalsIgnoreCase("fp"))) {
            String[] rest = java.util.Arrays.copyOfRange(args, 1, args.length);
            return new cn.starfallplain.sfpmain.bot.BotCommand(plugin).suggest(source, rest);
        }
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 1 ? args[0] : "");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("sweep")) {
            return filter(List.of("on", "off", "except"), args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("sweep") && args[1].equalsIgnoreCase("except")) {
            return filter(List.of("add", "remove"), args[2]);
        }
        if (args.length == 4 && args[0].equalsIgnoreCase("sweep") && args[1].equalsIgnoreCase("except")
                && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) {
            return filter(Bukkit.getWorlds().stream().map(org.bukkit.World::getName).toList(), args[3]);
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("db")) {
            return filter(DB_SUBCOMMANDS, args[1]);
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("db")
                && (args[1].equalsIgnoreCase("homes") || args[1].equalsIgnoreCase("back"))) {
            return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[2]);
        }
        return List.of();
    }

    // ==================== 子命令 ====================

    private void sweep(CommandSender sender, String[] args) {
        var store = plugin.getSweepExceptionStore();
        if (store == null || !store.isAvailable()) {
            sender.sendMessage(plugin.getMessage("clean.database-error", "<red>扫地管理数据库不可用。</red>"));
            return;
        }
        if (args.length == 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            boolean enabled = args[1].equalsIgnoreCase("on");
            if (!store.setEnabled(enabled)) {
                sender.sendMessage(plugin.getMessage("clean.database-error", "<red>扫地管理数据库不可用。</red>"));
                return;
            }
            sender.sendMessage(plugin.getMessage(enabled ? "clean.enabled" : "clean.disabled", enabled
                    ? "<green>自动扫地已开启。</green>" : "<yellow>自动扫地已关闭。</yellow>"));
            return;
        }
        if (args.length == 6 && args[1].equalsIgnoreCase("except")
                && (args[2].equalsIgnoreCase("add") || args[2].equalsIgnoreCase("remove"))) {
            org.bukkit.World world = Bukkit.getWorld(args[3]);
            if (world == null) {
                plugin.getConfigManager().messages().send(sender, "clean.world-not-found", "<red>找不到世界：{world}</red>", java.util.Map.of("world", args[3]));
                return;
            }
            try {
                int x = Integer.parseInt(args[4]);
                int z = Integer.parseInt(args[5]);
                boolean add = args[2].equalsIgnoreCase("add");
                Boolean changed = store.setException(world.getName(), x, z, add);
                if (changed == null) {
                    sender.sendMessage(plugin.getMessage("clean.database-error", "<red>扫地管理数据库不可用。</red>"));
                    return;
                }
                String key = changed ? (add ? "clean.exception-added" : "clean.exception-removed") : "clean.exception-unchanged";
                String fallback = changed ? (add ? "<green>已添加例外区块 {world} {x} {z}。</green>" : "<green>已移除例外区块 {world} {x} {z}。</green>") : "<yellow>该例外区块状态未改变。</yellow>";
                plugin.getConfigManager().messages().send(sender, key, fallback, java.util.Map.of("world", world.getName(), "x", args[4], "z", args[5]));
            } catch (NumberFormatException ex) {
                sender.sendMessage(plugin.getMessage("clean.invalid-coordinate", "<red>区块坐标必须是整数。</red>"));
            }
            return;
        }
        sweepUsage(sender);
    }

    private void reload(CommandSender sender) {
        if (plugin.reloadAll()) {
            sender.sendMessage(plugin.getMessage("common.reload-success", "<green>[星落平原] 配置已重载。</green>"));
            DbDebug.send(sender, "<gray>提示：开关类的修改需重启服务器才能完全生效。</gray>");
        } else {
            sender.sendMessage(plugin.getMessage("common.reload-failed", "<red>[星落平原] 重载失败。</red>"));
        }
    }

    private void status(CommandSender sender) {
        DbDebug.send(sender, "<dark_gray>========== 运行状态 ==========</dark_gray>");
        DbDebug.send(sender, "<gray>插件版本： <white>" + plugin.getPluginMeta().getVersion() + "</white></gray>");
        DbDebug.send(sender, "<gray>服务器： <white>" + Bukkit.getVersion() + "</white></gray>");
        DbDebug.send(sender, "<gray>在线玩家： <white>" + Bukkit.getOnlinePlayers().size() + "</white></gray>");
        SelfTest.printRuntime(plugin, sender);
    }

    private void db(CommandSender sender, String[] args) {
        String sub = args.length > 1 ? args[1].toLowerCase() : "";
        String playerArg = args.length > 2 ? args[2] : null;

        // 仅处罚模式下没有传送库，/sfp db 改为展示处罚库，并跳过传送专属子命令。
        if (plugin.getConfigManager().global().isPunishOnly()) {
            if (sub.isEmpty()) {
                DbDebug.punishOverview(plugin, sender);
            } else {
                DbDebug.send(sender, "<red>仅处罚模式下 /sfp db 只提供概览（无传送库相关子命令）。</red>");
                DbDebug.send(sender, "<gray>提示：处罚记录请用 <white>/sfpcheck</white> 或 "
                        + "<white>/sfpcheck &lt;玩家名&gt;</white> 查询。</gray>");
            }
            return;
        }

        switch (sub) {
            case "" -> DbDebug.overview(plugin, sender);
            case "tables" -> DbDebug.tables(plugin, sender);
            case "homes" -> DbDebug.homes(plugin, sender, playerArg);
            case "warps" -> DbDebug.warps(plugin, sender);
            case "back" -> DbDebug.back(plugin, sender, playerArg);
            case "check" -> DbDebug.check(plugin, sender);
            case "checkpoint" -> DbDebug.checkpoint(plugin, sender);
            default -> {
                var messages = plugin.getConfigManager().messages();
                messages.send(sender, "common.unknown-subcommand-input",
                        "<red>未知子命令：</red><white>{input}</white>", Map.of("input", sub));
                String suggestion = closestMatch(DB_SUBCOMMANDS, sub);
                if (suggestion != null) {
                    messages.send(sender, "common.unknown-subcommand-suggest",
                            "<gray>你是不是想输入 </gray><white>{command}</white><gray>？</gray>",
                            Map.of("command", "/sfp db " + suggestion));
                }
                DbDebug.send(sender, "<gray>可用： <white>" + String.join(" ", DB_SUBCOMMANDS) + "</white></gray>");
            }
        }
    }

    private void usage(CommandSender sender) {
        DbDebug.send(sender, "<dark_gray>========== /sfp 用法 ==========</dark_gray>");
        DbDebug.send(sender, "<gray>权限：<white>sfpmenu.admin</white>（sweep 需 <white>sfpmenu.clean.manage</white>，"
                + "player 需 <white>sfpmenu.bot</white>）</gray>");
        DbDebug.send(sender, "<white>/sfp reload</white> <dark_gray>重载配置（开关类改动需重启）</dark_gray>");
        DbDebug.send(sender, "<white>/sfp status</white> <dark_gray>运行状态与各模块实时数据</dark_gray>");
        DbDebug.send(sender, "<white>/sfp db [子命令]</white> <dark_gray>数据库概览，子命令：</dark_gray>"
                + "<white> " + String.join(" ", DB_SUBCOMMANDS) + "</white>");
        DbDebug.send(sender, "<white>/sfp test</white> <dark_gray>全量功能自检并输出报告</dark_gray>");
        DbDebug.send(sender, "<white>/sfp sweep on|off</white> <dark_gray>开启 / 关闭自动扫地"
                + "（需 sfpmenu.clean.manage）</dark_gray>");
        DbDebug.send(sender, "<white>/sfp sweep except add|remove &lt;世界&gt; &lt;区块X&gt; &lt;区块Z&gt;</white>"
                + " <dark_gray>维护扫地例外区块</dark_gray>");
        DbDebug.send(sender, "<white>/sfp player|fp &lt;子命令&gt;</white> <dark_gray>假人管理（需 sfpmenu.bot，"
                + "输入 /sfp player help 查看用法）</dark_gray>");
    }

    /** 扫地管理的用法：参数不对时只提示这几行，不打印整个 /sfp 用法 */
    private void sweepUsage(CommandSender sender) {
        DbDebug.send(sender, "<dark_gray>========== /sfp sweep 用法 ==========</dark_gray>");
        DbDebug.send(sender, "<white>/sfp sweep on|off</white> <dark_gray>开启 / 关闭自动扫地</dark_gray>");
        DbDebug.send(sender, "<white>/sfp sweep except add|remove &lt;世界&gt; &lt;区块X&gt; &lt;区块Z&gt;</white>"
                + " <dark_gray>维护扫地例外区块</dark_gray>");
    }

    // ==================== 提示 ====================

    /** 未知子命令：回显输入 → 最接近的子命令 → 用法入口（不打印整段用法） */
    private void unknownSubcommand(CommandSender sender, String raw) {
        var messages = plugin.getConfigManager().messages();
        messages.send(sender, "common.unknown-subcommand-input",
                "<red>未知子命令：</red><white>{input}</white>", Map.of("input", raw));
        String suggestion = closestMatch(SUBCOMMANDS, raw);
        if (suggestion != null) {
            messages.send(sender, "common.unknown-subcommand-suggest",
                    "<gray>你是不是想输入 </gray><white>{command}</white><gray>？</gray>",
                    Map.of("command", "/sfp " + suggestion));
        }
        messages.send(sender, "common.unknown-subcommand-hint",
                "<gray>输入 </gray><white>{command}</white><gray> 查看完整用法。</gray>",
                Map.of("command", "/sfp"));
    }

    /** 无权限：优先带权限节点的新文案；老配置缺键时退回旧文案 */
    private void noPermission(CommandSender sender, String permission) {
        var messages = plugin.getConfigManager().messages();
        if (messages.has("common.no-permission-with-node")) {
            messages.send(sender, "common.no-permission-with-node",
                    "<red>你没有权限使用此命令（需要 {permission}）。</red>",
                    Map.of("permission", permission));
            return;
        }
        sender.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
    }

    /**
     * 在候选子命令里挑最接近的一个：先看输入是不是某个子命令的开头，
     * 再按编辑距离（阈值随长度放宽到 1~2）。都没命中返回 null。
     */
    private static String closestMatch(List<String> options, String input) {
        String lower = input.toLowerCase();
        for (String option : options) {
            if (option.startsWith(lower)) return option;
        }
        String best = null;
        int bestDistance = Integer.MAX_VALUE;
        for (String option : options) {
            int distance = editDistance(lower, option);
            if (distance < bestDistance) {
                bestDistance = distance;
                best = option;
            }
        }
        return bestDistance <= (lower.length() <= 3 ? 1 : 2) ? best : null;
    }

    /** 标准 Levenshtein 编辑距离（只用于提示，输入都很短） */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) previous[j] = j;
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                current[j] = Math.min(Math.min(current[j - 1] + 1, previous[j] + 1),
                        previous[j - 1] + cost);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }
}
