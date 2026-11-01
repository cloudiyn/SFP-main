package cn.starfallplain.sfpmain.teleport.command;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.teleport.TpaManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /tpa、/tpahere、/tpaccept、/tpdeny、/tpaui —— 玩家间传送请求。
 * <p>
 * {@link BasicCommand} 拿不到命令标签，所以每个标签各注册一个本类实例，用构造参数 {@code action} 区分动作。
 * <p>
 * 注意「接受 / 拒绝」也做成命令而不是纯界面回调：聊天 TUI 的按钮、弹窗界面的按钮、手动输入
 * 三条路径最终都落到这两个命令上，行为完全一致，也不会出现「界面按钮坏了就彻底没法响应」的情况。
 * <p>
 * {@code /tpaui} 切换「回应界面」形式（弹窗 / 聊天按钮），偏好存 data/settings.db（旧版根目录自动迁移）；
 * 回应界面里的「切换界面」按钮执行的就是它。
 */
public class TpaCommand implements BasicCommand {

    private final SfpMain plugin;
    private final TpaManager tpaManager;
    /** 动作：tpa / tpahere / tpaccept / tpdeny / tpaui */
    private final String action;

    public TpaCommand(SfpMain plugin, TpaManager tpaManager, String action) {
        this.plugin = plugin;
        this.tpaManager = tpaManager;
        this.action = action;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessage("common.player-only", "<red>该命令只能由玩家执行。</red>"));
            return;
        }
        if (!player.hasPermission("sfpmenu.teleport")) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return;
        }
        if (!tpaManager.isEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }

        switch (action) {
            case "tpa" -> request(player, args, TpaManager.Type.TO);
            case "tpahere" -> request(player, args, TpaManager.Type.HERE);
            case "tpaccept" -> tpaManager.accept(player, args.length > 0 ? args[0] : null);
            case "tpdeny" -> tpaManager.deny(player, args.length > 0 ? args[0] : null);
            case "tpaui" -> tpaManager.switchUi(player);
            default -> player.sendMessage(plugin.getMessage("common.unknown-subcommand",
                    "<red>未知子命令。</red>"));
        }
    }

    private void request(Player player, String[] args, TpaManager.Type type) {
        if (args.length == 0) {
            Map<String, String> ph = new HashMap<>();
            player.sendMessage(plugin.getMessage(type == TpaManager.Type.TO ? "tpa.usage-tpa" : "tpa.usage-tpahere",
                    type == TpaManager.Type.TO
                            ? "<red>用法：/tpa <玩家名> —— 请求传送到对方身边。</red>"
                            : "<red>用法：/tpahere <玩家名> —— 请求对方传送到你身边。</red>"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", args[0]);
            plugin.getConfigManager().messages().send(player, "tpa.target-offline",
                    "<red>玩家「{name}」不在线。</red>", ph);
            return;
        }
        tpaManager.request(player, target, type);
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player player)) return List.of();
        if (args.length > 1) return List.of();

        String prefix = args.length == 1 ? args[0].toLowerCase() : "";
        List<String> options;
        if (action.equals("tpaccept") || action.equals("tpdeny")) {
            // 有待处理请求时优先补全发起者名字
            options = tpaManager.pendingFromNames(player);
            if (options.isEmpty()) options = onlineNames(player, true);
        } else {
            options = onlineNames(player, true);
        }
        List<String> result = new ArrayList<>();
        for (String name : options) {
            if (name.toLowerCase().startsWith(prefix)) result.add(name);
        }
        return result;
    }

    /** 在线玩家名；excludeSelf 为 true 时排除自己 */
    private static List<String> onlineNames(Player self, boolean excludeSelf) {
        List<String> names = new ArrayList<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (excludeSelf && p.getUniqueId().equals(self.getUniqueId())) continue;
            names.add(p.getName());
        }
        return names;
    }
}
