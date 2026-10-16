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

/**
 * {@code /sfp} —— 星落平原管理 / 调试命令（需要 {@code sfpmenu.admin}）。
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
 * </pre>
 *
 * 子命令的输出文案写死在本包内：诊断条目多且高度动态（表名、行数、列名、失效条目……），
 * 塞进 messages.yml 既难读也难维护。玩家可见的常规文案仍然走 messages.yml。
 */
public final class SfpCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of("reload", "status", "db", "test");
    private static final List<String> DB_SUBCOMMANDS =
            List.of("tables", "homes", "warps", "back", "check", "checkpoint");

    private final SfpMain plugin;

    public SfpCommand(SfpMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("sfpmenu.admin")) {
            sender.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
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
            default -> {
                sender.sendMessage(plugin.getMessage("common.unknown-subcommand",
                        "<red>未知子命令，输入 /sfp 查看用法。</red>"));
                usage(sender);
            }
        }
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 1 ? args[0] : "");
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
                DbDebug.send(sender, "<red>未知的 db 子命令：</red><white>" + sub + "</white>");
                DbDebug.send(sender, "<gray>可用： <white>" + String.join(" ", DB_SUBCOMMANDS) + "</white></gray>");
            }
        }
    }

    private void usage(CommandSender sender) {
        DbDebug.send(sender, "<dark_gray>========== /sfp 用法 ==========</dark_gray>");
        DbDebug.send(sender, "<white>/sfp reload</white> <dark_gray>重载配置（开关类改动需重启）</dark_gray>");
        DbDebug.send(sender, "<white>/sfp status</white> <dark_gray>运行状态与各模块实时数据</dark_gray>");
        DbDebug.send(sender, "<white>/sfp db</white> <dark_gray>数据库概览，子命令：</dark_gray>"
                + "<white> " + String.join(" ", DB_SUBCOMMANDS) + "</white>");
        DbDebug.send(sender, "<white>/sfp test</white> <dark_gray>全量功能自检并输出报告</dark_gray>");
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }
}
