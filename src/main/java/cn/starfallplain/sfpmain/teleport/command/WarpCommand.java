package cn.starfallplain.sfpmain.teleport.command;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import cn.starfallplain.sfpmain.util.NameUtil;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /warp 系列命令：
 * <ul>
 *   <li>{@code /warp} —— 打开传送点列表 GUI</li>
 *   <li>{@code /warp <名称>} —— 传送到指定传送点</li>
 *   <li>{@code /setwarp <名称>} —— 创建传送点（需权限）</li>
 *   <li>{@code /delwarp <名称>} —— 删除传送点（需权限）</li>
 *   <li>{@code /warps} —— 列出所有传送点</li>
 * </ul>
 * <p>
 * Paper 插件不能用 plugin.yml 声明命令，四个标签各注册一个实例，
 * 用构造参数 {@code action} 区分行为（{@link BasicCommand} 拿不到命令标签）。
 */
public class WarpCommand implements BasicCommand {

    /** 动作：warp / setwarp / delwarp / warps */
    private final String action;

    private final SfpMain plugin;
    private final TeleportManager manager;

    public WarpCommand(SfpMain plugin, TeleportManager manager, String action) {
        this.plugin = plugin;
        this.manager = manager;
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
        TeleportConfig config = manager.getConfig();
        if (!config.isWarpEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        if (!manager.isStorageAvailable()) {
            player.sendMessage(plugin.getMessage("teleport.storage-error",
                    "<red>传送数据库不可用，请联系管理员。</red>"));
            return;
        }

        // 按注册时的动作分派（BasicCommand 拿不到命令标签）
        if (action.equals("setwarp")) {
            setWarp(player, args);
            return;
        }
        if (action.equals("delwarp")) {
            delWarp(player, args);
            return;
        }
        if (action.equals("warps")) {
            listWarps(player);
            return;
        }

        if (args.length == 0) {
            // 与菜单里的「传送点」按钮同一个入口：按玩家偏好分流 dialogUI / 箱子界面
            MenuManager.openWarpList(plugin, player);
            return;
        }
        teleportWarp(player, args[0]);
    }

    private boolean setWarp(Player player, String[] args) {
        String permission = manager.getConfig().getWarpSetPermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getMessage("warp.usage-setwarp",
                    "<red>用法：/setwarp <名称></red>"));
            return true;
        }
        String name = args[0];
        if (!NameUtil.isValidName(name)) {
            player.sendMessage(plugin.getMessage("warp.invalid-name",
                    "<red>传送点名只能包含中文、字母、数字、下划线，长度 1~16。</red>"));
            return true;
        }

        StoredLocation loc = StoredLocation.of(player.getLocation());
        if (loc == null || !manager.getWarpStore().save(name, loc)) {
            player.sendMessage(plugin.getMessage("teleport.failed", "<red>保存失败，请稍后重试。</red>"));
            return true;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        ph.put("location", loc.describe());
        plugin.getConfigManager().messages().send(player, "warp.set-success",
                "<green>已创建传送点「{name}」（{location}）。</green>", ph);
        return true;
    }

    private boolean delWarp(Player player, String[] args) {
        String permission = manager.getConfig().getWarpDeletePermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return true;
        }
        if (args.length == 0) {
            player.sendMessage(plugin.getMessage("warp.usage-delwarp",
                    "<red>用法：/delwarp <名称></red>"));
            return true;
        }
        String name = args[0];
        if (!manager.getWarpStore().delete(name)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "warp.not-found",
                    "<red>不存在名为「{name}」的传送点。</red>", ph);
            return true;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "warp.del-success",
                "<green>已删除传送点「{name}」。</green>", ph);
        return true;
    }

    private boolean listWarps(Player player) {
        List<String> names = manager.getWarpStore().listNames();
        if (names.isEmpty()) {
            player.sendMessage(plugin.getMessage("warp.none", "<yellow>当前没有任何传送点。</yellow>"));
            return true;
        }
        Map<String, String> headerPh = new HashMap<>();
        headerPh.put("count", String.valueOf(names.size()));
        plugin.getConfigManager().messages().send(player, "warp.list-header",
                "<aqua>传送点（{count}）：</aqua>", headerPh);
        for (String name : names) {
            StoredLocation loc = manager.getWarpStore().get(name);
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            ph.put("location", loc != null ? loc.describe() : "?");
            plugin.getConfigManager().messages().send(player, "warp.list-entry",
                    "<gray> - </gray><white>{name}</white> <dark_gray>({location})</dark_gray>", ph);
        }
        return true;
    }

    private boolean teleportWarp(Player player, String name) {
        manager.teleportWarp(player, name);
        return true;
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player)) return List.of();
        List<String> result = new ArrayList<>();
        // 仅 /warp 与 /delwarp 需要补全传送点名
        if (args.length == 1 && (action.equals("warp") || action.equals("delwarp"))) {
            String prefix = args[0].toLowerCase();
            for (String name : manager.getWarpStore().listNames()) {
                if (name.toLowerCase().startsWith(prefix)) result.add(name);
            }
        }
        return result;
    }
}
