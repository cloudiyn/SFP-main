package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * {@code /bot} —— 假人管理（需要 {@code sfpmenu.bot}）。
 *
 * <pre>
 *   /bot create &lt;名字&gt;                      在当前所在位置创建假人（玩家执行）
 *   /bot create &lt;名字&gt; &lt;世界&gt; &lt;x&gt; &lt;y&gt; &lt;z&gt;     在指定坐标创建（控制台也可执行）
 *   /bot remove &lt;名字&gt;                       删除假人
 *   /bot list                                列出全部假人及在线状态
 *   /bot removeall                           删除全部假人
 * </pre>
 *
 * 假人是真玩家实体（见 {@link NmsBotFactory}），除了保持所在区块加载之外不做任何事。
 * 创建出来的假人会记在 {@code bots.yml}，重启后自动重建。
 */
public class BotCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of("create", "remove", "list", "removeall");
    /** 坐标参数个数：/bot create <名字> <世界> <x> <y> <z> → create,name,world,x,y,z */
    private static final int COORD_ARGS = 6;

    private final SfpMain plugin;

    public BotCommand(SfpMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("sfpmenu.bot")) {
            sender.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return;
        }
        BotManager manager = plugin.getBotManager();
        if (manager == null || !manager.getConfig().isEnabled()) {
            sender.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }

        switch (args[0].toLowerCase()) {
            case "create" -> create(sender, manager, args);
            case "remove" -> {
                if (args.length < 2) {
                    send(sender, "<red>用法：/bot remove <名字></red>");
                    return;
                }
                manager.remove(sender, args[1]);
            }
            case "list" -> manager.list(sender);
            case "removeall" -> manager.removeAll(sender);
            default -> {
                send(sender, "<red>未知子命令：</red><white>" + args[0] + "</white>");
                usage(sender);
            }
        }
    }

    private void create(CommandSender sender, BotManager manager, String[] args) {
        if (args.length < 2) {
            send(sender, "<red>用法：/bot create <名字> [世界 x y z]</red>");
            return;
        }
        String name = args[1];
        World world;
        Location loc;

        if (args.length >= COORD_ARGS) {
            world = Bukkit.getWorld(args[2]);
            if (world == null) {
                plugin.getConfigManager().messages().send(sender, "bot.world-not-found",
                        "<red>找不到世界「{world}」。</red>", Map.of("world", args[2]));
                return;
            }
            try {
                loc = new Location(world,
                        Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            } catch (NumberFormatException e) {
                plugin.getConfigManager().messages().send(sender, "bot.bad-coords",
                        "<red>坐标格式不正确，应为数字。</red>", Map.of());
                return;
            }
        } else if (args.length > 2) {
            // 给了世界 / 坐标但没写全（例如 /bot create 名字 world 100 64）：
            // 必须明确报错，不能静默退回「玩家当前位置」把这两个参数悄悄丢掉
            plugin.getConfigManager().messages().send(sender, "bot.usage-coords",
                    "<red>坐标要么不写，要么写全：/bot create <名字> <世界> <x> <y> <z></red>",
                    Map.of());
            return;
        } else if (sender instanceof Player player) {
            world = player.getWorld();
            loc = player.getLocation();
        } else {
            plugin.getConfigManager().messages().send(sender, "bot.need-coords",
                    "<red>控制台执行时必须给出坐标：/bot create <名字> <世界> <x> <y> <z></red>",
                    Map.of());
            return;
        }

        manager.create(sender, name, world, loc);
    }

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        BotManager manager = plugin.getBotManager();
        if (manager == null) return List.of();

        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 1 ? args[0] : "");
        }
        String sub = args[0].toLowerCase();
        if (args.length == 2) {
            if (sub.equals("remove")) return filter(manager.names(), args[1]);
            return List.of();
        }
        // /bot create <名字> <世界> ... → 补全世界名
        if (sub.equals("create") && args.length == 3) {
            List<String> worlds = new ArrayList<>();
            for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
            return filter(worlds, args[2]);
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }

    private void usage(CommandSender sender) {
        send(sender, "<dark_gray>========== /bot 用法 ==========</dark_gray>");
        send(sender, "<white>/bot create <名字></white> <dark_gray>在当前位置创建假人</dark_gray>");
        send(sender, "<white>/bot create <名字> <世界> <x> <y> <z></white> <dark_gray>在指定坐标创建</dark_gray>");
        send(sender, "<white>/bot remove <名字></white> <dark_gray>删除假人</dark_gray>");
        send(sender, "<white>/bot list</white> <dark_gray>列出全部假人</dark_gray>");
        send(sender, "<white>/bot removeall</white> <dark_gray>删除全部假人</dark_gray>");
    }

    private void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(Messages.deserialize(miniMessage));
    }
}
