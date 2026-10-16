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
 * /home 系列命令：
 * <ul>
 *   <li>{@code /home} —— 打开家列表 GUI</li>
 *   <li>{@code /home <名称>} —— 传送到指定家</li>
 *   <li>{@code /sethome [名称]} —— 设置家（默认名 "home"）</li>
 *   <li>{@code /delhome <名称>} —— 删除家</li>
 *   <li>{@code /homes} —— 列出所有家</li>
 * </ul>
 * <p>
 * Paper 插件不能用 plugin.yml 声明命令，四个标签各注册一个实例，
 * 用构造参数 {@code action} 区分行为（{@link BasicCommand} 拿不到命令标签）。
 */
public class HomeCommand implements BasicCommand {

    /** 动作：home / sethome / delhome / homes */
    private final String action;

    private final SfpMain plugin;
    private final TeleportManager manager;

    public HomeCommand(SfpMain plugin, TeleportManager manager, String action) {
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
        if (!config.isHomeEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        if (!manager.isStorageAvailable()) {
            player.sendMessage(plugin.getMessage("teleport.storage-error",
                    "<red>传送数据库不可用，请联系管理员。</red>"));
            return;
        }

        // 按注册时的动作分派（BasicCommand 拿不到命令标签）
        if (action.equals("sethome")) {
            setHome(player, args);
            return;
        }
        if (action.equals("delhome")) {
            delHome(player, args);
            return;
        }
        if (action.equals("homes")) {
            listHomes(player);
            return;
        }

        // /home 或 /home <名称>
        if (args.length == 0) {
            // 与菜单里的「我的家」按钮同一个入口：按玩家偏好分流 dialogUI / 箱子界面
            MenuManager.openHomeList(plugin, player);
            return;
        }
        teleportHome(player, args[0]);
    }

    private boolean setHome(Player player, String[] args) {
        TeleportConfig config = manager.getConfig();

        long cd = manager.getSetHomeCooldownRemaining(player);
        if (cd > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("seconds", String.valueOf(cd));
            plugin.getConfigManager().messages().send(player, "home.set-cooldown",
                    "<red>设置家冷却中，请等待 {seconds} 秒。</red>", ph);
            return true;
        }

        String name = args.length > 0 ? args[0] : "home";
        if (!NameUtil.isValidName(name)) {
            player.sendMessage(plugin.getMessage("home.invalid-name",
                    "<red>家名只能包含中文、字母、数字、下划线，长度 1~16。</red>"));
            return true;
        }

        int max = config.getHomeMaxHomes();
        boolean isNew = manager.getHomeStore().get(player.getUniqueId(), name) == null;
        if (isNew && max >= 0 && manager.getHomeStore().count(player.getUniqueId()) >= max
                && !player.hasPermission("sfpmenu.home.bypass-limit")) {
            Map<String, String> ph = new HashMap<>();
            ph.put("max", String.valueOf(max));
            plugin.getConfigManager().messages().send(player, "home.limit-reached",
                    "<red>你的家数量已达上限（{max} 个），请先删除一些。</red>", ph);
            return true;
        }

        StoredLocation loc = StoredLocation.of(player.getLocation());
        if (loc == null || !manager.getHomeStore().save(player.getUniqueId(), name, loc)) {
            player.sendMessage(plugin.getMessage("teleport.failed", "<red>保存失败，请稍后重试。</red>"));
            return true;
        }
        manager.markSetHomeCooldown(player);

        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        ph.put("location", loc.describe());
        plugin.getConfigManager().messages().send(player, "home.set-success",
                "<green>已设置家「{name}」（{location}）。</green>", ph);
        return true;
    }

    private boolean delHome(Player player, String[] args) {
        if (args.length == 0) {
            player.sendMessage(plugin.getMessage("home.usage-delhome",
                    "<red>用法：/delhome <名称></red>"));
            return true;
        }
        String name = args[0];
        if (!manager.getHomeStore().delete(player.getUniqueId(), name)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "home.not-found",
                    "<red>不存在名为「{name}」的家。</red>", ph);
            return true;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "home.del-success",
                "<green>已删除家「{name}」。</green>", ph);
        return true;
    }

    private boolean listHomes(Player player) {
        List<String> names = manager.getHomeStore().listNames(player.getUniqueId());
        if (names.isEmpty()) {
            player.sendMessage(plugin.getMessage("home.none", "<yellow>你还没有设置任何家。</yellow>"));
            return true;
        }
        Map<String, String> headerPh = new HashMap<>();
        headerPh.put("count", String.valueOf(names.size()));
        plugin.getConfigManager().messages().send(player, "home.list-header",
                "<aqua>你的家（{count}）：</aqua>", headerPh);
        for (String name : names) {
            StoredLocation loc = manager.getHomeStore().get(player.getUniqueId(), name);
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            ph.put("location", loc != null ? loc.describe() : "?");
            plugin.getConfigManager().messages().send(player, "home.list-entry",
                    "<gray> - </gray><white>{name}</white> <dark_gray>({location})</dark_gray>", ph);
        }
        return true;
    }

    private boolean teleportHome(Player player, String name) {
        manager.teleportHome(player, name);
        return true;
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player player)) return List.of();
        List<String> result = new ArrayList<>();

        // 仅 /home 与 /delhome 需要补全家名
        if (args.length == 1 && (action.equals("home") || action.equals("delhome"))) {
            String prefix = args[0].toLowerCase();
            for (String name : manager.getHomeStore().listNames(player.getUniqueId())) {
                if (name.toLowerCase().startsWith(prefix)) result.add(name);
            }
        }
        return result;
    }
}
