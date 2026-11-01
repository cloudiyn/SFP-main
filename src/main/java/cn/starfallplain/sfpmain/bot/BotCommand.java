package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.bot.BotManager.Bot;
import cn.starfallplain.sfpmain.config.Messages;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code /sfp player}（简写 {@code /sfp fp}）—— 假人管理。
 * <p>
 * 由 {@code SfpCommand} 在 {@code /sfp} 的子命令位转调本命令（权限走
 * {@code sfpmenu.bot}，与 {@code /sfp} 的管理权限相互独立）。
 * <p>
 * 大部分子命令的「假人名」参数**可以省略**：省略时用 {@link BotManager#select} 设定的
 * 默认假人；第一个参数恰好是已存在的假人名时，会被当作假人名吃掉。
 * <p>
 * 未知子命令统一提示「未知子命令：xxx」+ 最接近的写法 + 用法入口，完整用法见
 * {@code /sfp player help}（不带子命令输入 {@code /sfp player} 同效）。
 * <pre>
 *   /sfp player spawn &lt;名字&gt; [世界 x y z]   在当前所在位置创建假人
 *   /sfp player kill [名字] / killall         移除假人 / 移除全部（需 sfpmenu.bot.killall）
 *   /sfp player list                          列出全部假人
 *   /sfp player select &lt;名字&gt; / selection    设默认假人 / 看默认假人
 *   /sfp player status [名字]                 状态（生命/饱食度/位置/设置/动作）
 *   /sfp player distance [名字]               与假人的距离
 *   /sfp player invsee [名字]                 打开假人背包（右键假人同效）
 *   /sfp player skin &lt;玩家名&gt; [名字]          复制某玩家的皮肤
 *   /sfp player respawn [名字]                复活死亡的假人
 *   /sfp player tp [名字] / tphere / tps      传送到假人 / 拉过来 / 交换位置
 *   /sfp player expme [名字]                  把假人的经验转给自己
 *   /sfp player cmd &lt;命令...&gt;                 以假人身份执行命令
 *   /sfp player drop / dropstack / dropinv [名字] 丢手持1个 / 丢整叠 / 丢整个背包
 *   /sfp player swap [名字] / hold &lt;1-9&gt; [名字] 主副手互换 / 切快捷栏
 *   /sfp player set [名字] &lt;键&gt; &lt;true|false&gt;   改假人设置
 *   /sfp player config list|set &lt;键&gt; &lt;值&gt;|clear 个人默认配置（下次创建时套用）
 *   /sfp player attack / mine / use / move [名字] 持续：攻击 / 挖掘 / 使用 / 移动
 *   /sfp player jump [名字]                   跳一次
 *   /sfp player sneak / sprint [名字] [on|off] 潜行 / 疾跑（省略 on|off 为切换）
 *   /sfp player look [名字] [x y z] / turn [名字] &lt;度数&gt; 看向坐标或缺省看向你 / 原地转向
 *   /sfp player ride [名字]                   骑上面前的实体（再执行一次下车）
 *   /sfp player sleep / wakeup [名字]         睡觉 / 起床
 *   /sfp player stop [名字]                   停止全部持续动作
 *   /sfp player help                          显示完整用法（不带子命令同效）
 * </pre>
 */
public class BotCommand implements BasicCommand {

    private static final List<String> SUBCOMMANDS = List.of(
            "help", "spawn", "kill", "killall", "list", "select", "selection", "status", "distance",
            "invsee", "skin", "respawn", "tp", "tphere", "tps", "expme", "cmd",
            "drop", "dropstack", "dropinv", "swap", "hold", "set", "config",
            "attack", "mine", "use", "move", "jump", "sneak", "sprint",
            "look", "turn", "ride", "sleep", "wakeup", "stop");
    private static final List<String> CONFIG_SUBCOMMANDS = List.of("list", "set", "clear");
    private static final List<String> SETTING_KEYS = List.of(
            "collidable", "invulnerable", "wolverine", "look_at_entity",
            "pickup_items", "replenish", "autofish", "skin");
    private static final List<String> BOOLEANS = List.of("true", "false");
    private static final List<String> HOTBAR_SLOTS = List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");
    /** 坐标参数个数：spawn <名字> <世界> <x> <y> <z> → spawn,name,world,x,y,z */
    private static final int COORD_ARGS = 6;
    /** 用法文案的兜底：与 messages.yml 的同名键保持一致（改文案时两处一起改） */
    private static final String HOLD_USAGE = "<red>用法：/sfp player hold <1-9> [假人名]</red>";
    private static final String LOOK_USAGE =
            "<red>用法：/sfp player look [名字] <x> <y> <z>（省略坐标则看向你；控制台必须给坐标）</red>";
    private static final String TURN_USAGE = "<red>用法：/sfp player turn [假人名] <度数>（正数右转）</red>";
    private static final String CONFIG_USAGE =
            "<red>用法：/sfp player config list|set <键> <true|false>|clear</red>";
    private static final String CONFIG_SET_USAGE =
            "<red>用法：/sfp player config set <键> <true|false></red>";
    private static final String SNEAK_USAGE =
            "<red>用法：/sfp player sneak [名字] [on|off]（省略 on|off 为切换）</red>";
    private static final String SPRINT_USAGE =
            "<red>用法：/sfp player sprint [名字] [on|off]（省略 on|off 为切换）</red>";

    private final SfpMain plugin;

    public BotCommand(SfpMain plugin) {
        this.plugin = plugin;
    }

    private BotManager manager() {
        return plugin.getBotManager();
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("sfpmenu.bot")) {
            noPermission(sender, "sfpmenu.bot");
            return;
        }
        BotManager manager = manager();
        if (manager == null || !manager.getConfig().isEnabled()) {
            sender.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        if (args.length == 0) {
            usage(sender);
            return;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "help" -> usage(sender);
            case "spawn" -> spawn(sender, manager, args);
            case "kill" -> {
                Bot bot = resolveBot(sender, manager, args, 1);
                if (bot != null) manager.remove(sender, bot.name);
            }
            case "killall" -> {
                if (!sender.hasPermission("sfpmenu.bot.killall")) {
                    noPermission(sender, "sfpmenu.bot.killall");
                    return;
                }
                manager.removeAll(sender);
            }
            case "list" -> manager.list(sender);
            case "select" -> {
                if (args.length < 2) {
                    send(sender, "bot.select-usage", "<red>用法：/sfp player select <名字></red>", Map.of());
                    return;
                }
                manager.select(sender, args[1]);
            }
            case "selection" -> manager.selection(sender);
            case "status" -> withBot(sender, manager, args, 1, bot -> manager.status(sender, bot));
            case "distance" -> withBot(sender, manager, args, 1, bot -> manager.distance(sender, bot));
            case "invsee" -> withBot(sender, manager, args, 1, bot -> manager.invsee(sender, bot));
            case "skin" -> skin(sender, manager, args);
            case "respawn" -> respawn(sender, manager, args);
            case "tp" -> withBot(sender, manager, args, 1, bot -> manager.teleportTo(sender, bot));
            case "tphere" -> withBot(sender, manager, args, 1, bot -> manager.teleportHere(sender, bot));
            case "tps" -> withBot(sender, manager, args, 1, bot -> manager.teleportSwap(sender, bot));
            case "expme" -> withBot(sender, manager, args, 1, bot -> manager.expMe(sender, bot));
            case "cmd" -> cmd(sender, manager, args);
            case "drop" -> withBot(sender, manager, args, 1, bot -> manager.drop(sender, bot));
            case "dropstack" -> withBot(sender, manager, args, 1, bot -> manager.dropStack(sender, bot));
            case "dropinv" -> withBot(sender, manager, args, 1, bot -> manager.dropInventory(sender, bot));
            case "swap" -> withBot(sender, manager, args, 1, bot -> manager.swap(sender, bot));
            case "hold" -> hold(sender, manager, args);
            case "set" -> set(sender, manager, args);
            case "config" -> config(sender, manager, args);
            case "attack" -> withBot(sender, manager, args, 1, bot -> manager.toggleAction(sender, bot, BotAction.ATTACK));
            case "mine" -> withBot(sender, manager, args, 1, bot -> manager.toggleAction(sender, bot, BotAction.MINE));
            case "use" -> withBot(sender, manager, args, 1, bot -> manager.toggleAction(sender, bot, BotAction.USE));
            case "move" -> withBot(sender, manager, args, 1, bot -> manager.toggleAction(sender, bot, BotAction.MOVE));
            case "jump" -> withBot(sender, manager, args, 1,
                    bot -> act(sender, manager, bot, p -> manager.actions().jump(p),
                            "bot.jump", "<green>假人「{name}」跳了一下。</green>"));
            case "sneak" -> sneak(sender, manager, args);
            case "sprint" -> sprint(sender, manager, args);
            case "look" -> look(sender, manager, args);
            case "turn" -> turn(sender, manager, args);
            case "ride" -> ride(sender, manager, args);
            case "sleep" -> withBot(sender, manager, args, 1,
                    bot -> act(sender, manager, bot, p -> manager.actions().sleep(p),
                            "bot.sleep", "<green>假人「{name}」已躺下睡觉。</green>"));
            case "wakeup" -> withBot(sender, manager, args, 1,
                    bot -> act(sender, manager, bot, p -> manager.actions().wakeup(p),
                            "bot.wakeup", "<green>假人「{name}」已起床。</green>"));
            case "stop" -> withBot(sender, manager, args, 1, bot -> manager.stopActions(sender, bot));
            default -> unknownSubcommand(sender, args[0]);
        }
    }

    // ==================== 子命令实现 ====================

    private void spawn(CommandSender sender, BotManager manager, String[] args) {
        if (args.length < 2) {
            send(sender, "bot.spawn-usage",
                    "<red>用法：/sfp player spawn <名字> [世界 x y z]</red>", Map.of());
            return;
        }
        String name = args[1];
        World world;
        Location loc;

        if (args.length >= COORD_ARGS) {
            world = Bukkit.getWorld(args[2]);
            if (world == null) {
                send(sender, "bot.world-not-found", "<red>找不到世界「{world}」。</red>",
                        Map.of("world", args[2]));
                return;
            }
            try {
                loc = new Location(world,
                        Double.parseDouble(args[3]), Double.parseDouble(args[4]), Double.parseDouble(args[5]));
            } catch (NumberFormatException e) {
                send(sender, "bot.bad-coords", "<red>坐标格式不正确，应为数字。</red>", Map.of());
                return;
            }
        } else if (args.length > 2) {
            // 给了世界 / 坐标但没写全（例如 spawn 名字 world 100 64）：
            // 必须明确报错，不能静默退回「玩家当前位置」把这两个参数悄悄丢掉
            send(sender, "bot.usage-coords",
                    "<red>坐标要么不写，要么写全：/sfp player spawn <名字> <世界> <x> <y> <z></red>",
                    Map.of());
            return;
        } else if (sender instanceof Player player) {
            world = player.getWorld();
            loc = player.getLocation();
        } else {
            send(sender, "bot.need-coords",
                    "<red>控制台执行时必须给出坐标：/sfp player spawn <名字> <世界> <x> <y> <z></red>",
                    Map.of());
            return;
        }

        manager.create(sender, name, world, loc);
    }

    private void skin(CommandSender sender, BotManager manager, String[] args) {
        if (args.length < 2) {
            send(sender, "bot.skin-usage",
                    "<red>用法：/sfp player skin <玩家名> [假人名]</red>", Map.of());
            return;
        }
        Bot bot = args.length > 2 && manager.hasName(args[2])
                ? manager.byName(args[2])
                : resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        manager.skin(sender, bot, args[1]);
    }

    private void respawn(CommandSender sender, BotManager manager, String[] args) {
        Bot bot = resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        Player online = manager.online(bot);
        if (online == null) {
            send(sender, "bot.offline", "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return;
        }
        if (!online.isDead()) {
            send(sender, "bot.respawn-alive",
                    "<yellow>假人「{name}」还活着，不需要复活。</yellow>", Map.of("name", bot.name));
            return;
        }
        if (manager.actions().respawn(online, bot)) {
            send(sender, "bot.respawned",
                    "<green>已复活假人「{name}」。</green>", Map.of("name", bot.name));
        } else {
            send(sender, "bot.respawn-failed",
                    "<red>复活失败（可能服务端在假人死亡时已将其踢出，需重新 spawn）。</red>", Map.of());
        }
    }

    private void cmd(CommandSender sender, BotManager manager, String[] args) {
        int index = 1;
        Bot bot;
        if (args.length > 1 && manager.hasName(args[1])) {
            bot = manager.byName(args[1]);
            index = 2;
        } else {
            bot = resolveBot(sender, manager, args, 1);
        }
        if (bot == null) return;
        if (args.length <= index) {
            send(sender, "bot.cmd-usage",
                    "<red>用法：/sfp player cmd [名字] <命令...>（假人需有对应权限，可用 /op 给它提权）</red>",
                    Map.of());
            return;
        }
        String command = String.join(" ", Arrays.copyOfRange(args, index, args.length));
        manager.command(sender, bot, command);
    }

    private void hold(CommandSender sender, BotManager manager, String[] args) {
        // 第一个参数是槽位号（1-9）而不是假人名
        if (args.length < 2) {
            send(sender, "bot.hold-usage", HOLD_USAGE, Map.of());
            return;
        }
        int slot;
        try {
            slot = Integer.parseInt(args[1]);
        } catch (NumberFormatException e) {
            send(sender, "bot.hold-usage", HOLD_USAGE, Map.of());
            return;
        }
        if (slot < 1 || slot > 9) {
            send(sender, "bot.hold-usage", HOLD_USAGE, Map.of());
            return;
        }
        Bot bot = args.length > 2 && manager.hasName(args[2])
                ? manager.byName(args[2])
                : resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        manager.hold(sender, bot, slot);
    }

    private void set(CommandSender sender, BotManager manager, String[] args) {
        int index = 1;
        Bot bot;
        if (args.length > 1 && manager.hasName(args[1])) {
            bot = manager.byName(args[1]);
            index = 2;
        } else {
            bot = resolveBot(sender, manager, args, 1);
        }
        if (bot == null) return;
        if (args.length < index + 2) {
            send(sender, "bot.set-usage",
                    "<red>用法：/sfp player set [名字] <键> <true|false>，可用键：collidable / invulnerable / "
                            + "wolverine / look_at_entity / pickup_items / replenish / autofish / skin</red>",
                    Map.of());
            return;
        }
        manager.setSetting(sender, bot, args[index], args[index + 1]);
    }

    private void config(CommandSender sender, BotManager manager, String[] args) {
        if (args.length < 2) {
            send(sender, "bot.config-usage", CONFIG_USAGE, Map.of());
            return;
        }
        switch (args[1].toLowerCase()) {
            case "list" -> manager.configList(sender);
            case "clear" -> manager.configClear(sender);
            case "set" -> {
                if (args.length < 4) {
                    send(sender, "bot.config-set-usage", CONFIG_SET_USAGE, Map.of());
                    return;
                }
                manager.configSet(sender, args[2], args[3]);
            }
            default -> send(sender, "bot.config-usage", CONFIG_USAGE, Map.of());
        }
    }

    private void sneak(CommandSender sender, BotManager manager, String[] args) {
        int rest = restIndex(args, 1);
        // 先校验显式开关值：写错就报用法，不要静默当成切换
        Boolean explicit = null;
        if (args.length > rest) {
            explicit = BotSettings.parseBoolean(args[rest]);
            if (explicit == null) {
                send(sender, "bot.sneak-usage", SNEAK_USAGE, Map.of());
                return;
            }
        }
        Bot bot = resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        Player online = requireOnline(sender, manager, bot);
        if (online == null) return;
        boolean on = explicit != null ? explicit : !online.isSneaking();
        manager.actions().sneak(online, on);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("state", on ? "开始" : "停止");
        send(sender, "bot.sneak-toggled", "<green>假人「{name}」{state}潜行。</green>", ph);
    }

    private void sprint(CommandSender sender, BotManager manager, String[] args) {
        int rest = restIndex(args, 1);
        // 先校验显式开关值：写错就报用法，不要静默当成切换
        Boolean explicit = null;
        if (args.length > rest) {
            explicit = BotSettings.parseBoolean(args[rest]);
            if (explicit == null) {
                send(sender, "bot.sprint-usage", SPRINT_USAGE, Map.of());
                return;
            }
        }
        Bot bot = resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        Player online = requireOnline(sender, manager, bot);
        if (online == null) return;
        boolean on = explicit != null ? explicit : !online.isSprinting();
        manager.actions().sprint(online, on);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("state", on ? "开始" : "停止");
        send(sender, "bot.sprint-toggled", "<green>假人「{name}」{state}疾跑。</green>", ph);
    }

    private void look(CommandSender sender, BotManager manager, String[] args) {
        int index = 1;
        Bot bot;
        boolean nameGiven = args.length > 1 && manager.hasName(args[1]);
        if (nameGiven) {
            bot = manager.byName(args[1]);
            index = 2;
        } else {
            bot = resolveBot(sender, manager, args, 1);
        }
        if (bot == null) return;
        Player online = requireOnline(sender, manager, bot);
        if (online == null) return;

        // 没给坐标 → 看向执行者
        if (args.length == index) {
            if (!(sender instanceof Player player)) {
                send(sender, "bot.look-usage", LOOK_USAGE, Map.of());
                return;
            }
            if (manager.actions().lookAt(online, player.getEyeLocation())) {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("name", bot.name);
                send(sender, "bot.looked", "<green>假人「{name}」已看向你。</green>", ph);
            }
            return;
        }
        if (args.length < index + 3) {
            send(sender, "bot.look-usage", LOOK_USAGE, Map.of());
            return;
        }
        double x;
        double y;
        double z;
        try {
            x = Double.parseDouble(args[index]);
            y = Double.parseDouble(args[index + 1]);
            z = Double.parseDouble(args[index + 2]);
        } catch (NumberFormatException e) {
            send(sender, "bot.look-usage", LOOK_USAGE, Map.of());
            return;
        }
        Location target = new Location(online.getWorld(), x, y, z);
        if (manager.actions().lookAt(online, target)) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", bot.name);
            send(sender, "bot.looked", "<green>假人「{name}」已转向目标点。</green>", ph);
        }
    }

    private void turn(CommandSender sender, BotManager manager, String[] args) {
        int index = 1;
        Bot bot;
        if (args.length > 1 && manager.hasName(args[1])) {
            bot = manager.byName(args[1]);
            index = 2;
        } else {
            bot = resolveBot(sender, manager, args, 1);
        }
        if (bot == null) return;
        if (args.length < index + 1) {
            send(sender, "bot.turn-usage", TURN_USAGE, Map.of());
            return;
        }
        double degrees;
        try {
            degrees = Double.parseDouble(args[index]);
        } catch (NumberFormatException e) {
            send(sender, "bot.turn-usage", TURN_USAGE, Map.of());
            return;
        }
        Player online = requireOnline(sender, manager, bot);
        if (online == null) return;
        if (manager.actions().turn(online, degrees)) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", bot.name);
            ph.put("degrees", String.valueOf(degrees));
            send(sender, "bot.turned", "<green>假人「{name}」已转向 {degrees} 度。</green>", ph);
        }
    }

    private void ride(CommandSender sender, BotManager manager, String[] args) {
        Bot bot = resolveBot(sender, manager, args, 1);
        if (bot == null) return;
        Player online = requireOnline(sender, manager, bot);
        if (online == null) return;

        Entity vehicle = online.getVehicle();
        if (vehicle != null) {
            online.leaveVehicle();
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", bot.name);
            send(sender, "bot.dismounted", "<green>假人「{name}」已下车。</green>", ph);
            return;
        }
        Location eye = online.getEyeLocation();
        RayTraceResult hit = online.getWorld()
                .rayTraceEntities(eye, eye.getDirection(), 5.0);
        if (hit == null || hit.getHitEntity() == null || hit.getHitEntity().equals(online)) {
            send(sender, "bot.ride-none",
                    "<yellow>假人「{name}」面前没有可骑乘的实体（再执行一次可下车）。</yellow>",
                    Map.of("name", bot.name));
            return;
        }
        if (manager.actions().ride(online, hit.getHitEntity())) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", bot.name);
            send(sender, "bot.ridden", "<green>假人「{name}」已骑上面前的实体。</green>", ph);
        } else {
            send(sender, "bot.ride-failed",
                    "<red>骑乘失败（该实体可能不允许骑乘）。</red>", Map.of());
        }
    }

    // ==================== 参数解析辅助 ====================

    /** 统一的「解析假人 + 执行操作」短路：bot 为 null（已发提示）时直接返回 */
    private void withBot(CommandSender sender, BotManager manager, String[] args, int from,
                         java.util.function.Consumer<BotManager.Bot> action) {
        Bot bot = resolveBot(sender, manager, args, from);
        if (bot == null) return;
        action.accept(bot);
    }

    /**
     * 解析可省略的假人名：第一个参数恰好是已存在的假人名时吃掉它，
     * 否则用执行者的默认假人（选择为空时会发提示并返回 null）。
     */
    private Bot resolveBot(CommandSender sender, BotManager manager, String[] args, int from) {
        String first = args.length > from ? args[from] : null;
        if (first != null && manager.hasName(first)) {
            return manager.byName(first);
        }
        return manager.resolve(sender, null);
    }

    /** 参数里吃了假人名时，剩余参数的起始下标 */
    private int restIndex(String[] args, int from) {
        return args.length > from && manager().hasName(args[from]) ? from + 1 : from;
    }

    /** 取在线假人；不在线时发提示并返回 null */
    private Player requireOnline(CommandSender sender, BotManager manager, BotManager.Bot bot) {
        Player online = manager.online(bot);
        if (online == null) {
            send(sender, "bot.offline", "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
        }
        return online;
    }

    /** 一次性动作的统一包装：取在线假人 → 执行 → 发结果 */
    private void act(CommandSender sender, BotManager manager, BotManager.Bot bot,
                     java.util.function.Function<Player, Boolean> action,
                     String messageKey, String fallback) {
        Player online = manager.online(bot);
        if (online == null) {
            send(sender, "bot.offline", "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return;
        }
        if (action.apply(online)) {
            send(sender, messageKey, fallback, Map.of("name", bot.name));
        } else {
            send(sender, "bot.action-failed", "<red>动作执行失败，请查看服务器日志。</red>", Map.of());
        }
    }

    // ==================== 补全 ====================

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        BotManager manager = manager();
        if (manager == null) return List.of();

        if (args.length <= 1) {
            return filter(SUBCOMMANDS, args.length == 1 ? args[0] : "");
        }
        String sub = args[0].toLowerCase();
        // 子命令本身写错时不再猜测参数，交给命令执行时的相似提示
        if (!SUBCOMMANDS.contains(sub)) return List.of();

        // 位置 2：子命令的第一个参数
        if (args.length == 2) {
            switch (sub) {
                case "spawn" -> { return List.of(); }
                case "config" -> { return filter(CONFIG_SUBCOMMANDS, args[1]); }
                case "hold" -> { return filter(HOTBAR_SLOTS, args[1]); }
                case "skin" -> {
                    return filter(Bukkit.getOnlinePlayers().stream().map(Player::getName).toList(), args[1]);
                }
                case "set" -> {
                    List<String> options = new ArrayList<>(manager.names());
                    options.addAll(SETTING_KEYS);
                    return filter(options, args[1]);
                }
                case "sneak", "sprint" -> {
                    List<String> options = new ArrayList<>(manager.names());
                    options.addAll(BOOLEANS);
                    return filter(options, args[1]);
                }
                case "list", "killall", "selection", "cmd", "help" -> { return List.of(); }
                default -> { return filter(manager.names(), args[1]); }
            }
        }

        // 位置 3
        if (args.length == 3) {
            if (sub.equals("set")) {
                // set <名字> <键> → 补全键；set <键> <值> → 补全布尔值
                if (manager.hasName(args[1])) return filter(SETTING_KEYS, args[2]);
                return filter(BOOLEANS, args[2]);
            }
            if (sub.equals("config") && args[1].equalsIgnoreCase("set")) {
                return filter(SETTING_KEYS, args[2]);
            }
            if (sub.equals("config")) return List.of();
            if (sub.equals("hold") || sub.equals("skin")) return filter(manager.names(), args[2]);
            if (sub.equals("spawn")) {
                List<String> worlds = new ArrayList<>();
                for (World w : Bukkit.getWorlds()) worlds.add(w.getName());
                return filter(worlds, args[2]);
            }
            return List.of();
        }

        // 位置 4：set <名字> <键> <值> 与 config set <键> <值> 都补布尔值
        if (args.length == 4) {
            if (sub.equals("set") && manager.hasName(args[1])
                    && BotSettings.byKey(args[2]) != null) {
                return filter(BOOLEANS, args[3]);
            }
            if (sub.equals("config") && args[1].equalsIgnoreCase("set")
                    && BotSettings.byKey(args[2]) != null) {
                return filter(BOOLEANS, args[3]);
            }
        }
        return List.of();
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase();
        return options.stream().filter(o -> o.toLowerCase().startsWith(lower)).toList();
    }

    // ==================== 输出 ====================

    private void usage(CommandSender sender) {
        for (String line : plugin.getRawMessageList("bot.usage", List.of())) {
            sender.sendMessage(Messages.deserialize(line));
        }
    }

    private void send(CommandSender sender, String key, String fallback, Map<String, String> placeholders) {
        if (fallback == null) {
            // 没有兜底文案时（一次性动作的成功提示），直接用 key 查配置，查不到用简单默认
            fallback = "<green>完成。</green>";
        }
        plugin.getConfigManager().messages().send(sender, key, fallback, placeholders);
    }

    // ==================== 提示 ====================

    /** 未知子命令：回显输入 → 最接近的子命令 → 用法入口（不打印整段用法） */
    private void unknownSubcommand(CommandSender sender, String raw) {
        send(sender, "common.unknown-subcommand-input",
                "<red>未知子命令：</red><white>{input}</white>", Map.of("input", raw));
        String suggestion = closestMatch(SUBCOMMANDS, raw);
        if (suggestion != null) {
            send(sender, "common.unknown-subcommand-suggest",
                    "<gray>你是不是想输入 </gray><white>{command}</white><gray>？</gray>",
                    Map.of("command", "/sfp player " + suggestion));
        }
        send(sender, "common.unknown-subcommand-hint",
                "<gray>输入 </gray><white>{command}</white><gray> 查看完整用法。</gray>",
                Map.of("command", "/sfp player help"));
    }

    /** 无权限：优先带权限节点的新文案；老配置缺键时退回旧文案 */
    private void noPermission(CommandSender sender, String permission) {
        Messages messages = plugin.getConfigManager().messages();
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
}
