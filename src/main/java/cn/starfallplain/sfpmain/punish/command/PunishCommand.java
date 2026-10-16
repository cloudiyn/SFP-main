package cn.starfallplain.sfpmain.punish.command;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.punish.PunishLog;
import cn.starfallplain.sfpmain.punish.PunishManager;
import cn.starfallplain.sfpmain.punish.Punishment;
import cn.starfallplain.sfpmain.punish.PunishmentType;
import cn.starfallplain.sfpmain.util.DurationParser;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 处罚系统命令族（7 个）：
 * <ul>
 *   <li>{@code /sfpcheck <处罚ID或玩家名>} —— 查询处罚历史</li>
 *   <li>{@code /sfpwarn <玩家> <原因>} —— 警告（仅在线玩家）</li>
 *   <li>{@code /sfpkick <玩家> <原因>} —— 踢出（仅在线玩家）</li>
 *   <li>{@code /sfpban <玩家> <时间> <原因>} —— 封禁（可离线）</li>
 *   <li>{@code /sfpmute <玩家> <时间> <原因>} —— 禁言（可离线）</li>
 *   <li>{@code /sfpunban <玩家>} —— 解封（<b>只从数据库查已封禁玩家</b>）</li>
 *   <li>{@code /sfpunmute <玩家>} —— 解禁（<b>只从数据库查已禁言玩家</b>）</li>
 * </ul>
 * <p>
 * {@link BasicCommand} 拿不到命令标签，故每个标签各注册一个本类实例，用构造参数 {@code action} 区分。
 * 权限校验一律手动做（不实现 {@code BasicCommand#permission()}）——否则无权限者眼里命令会「不存在」，
 * 反而让人以为命令写错了。
 * <p>
 * 命令反馈文案统一走 messages.yml 的 {@code punish.*} 键；「踢下线整屏 / 禁言提示」
 * 这类需要高度自定义的文案则放在 punish.yml（见 {@link cn.starfallplain.sfpmain.config.module.PunishConfig}）。
 */
public class PunishCommand implements BasicCommand {

    /** 时间列格式（SimpleDateFormat 非线程安全，命令都在主线程执行，够用） */
    private static final String TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";

    private final SfpMain plugin;
    private final PunishManager manager;
    /** 动作：check / warn / kick / ban / mute / unban / unmute */
    private final String action;

    public PunishCommand(SfpMain plugin, PunishManager manager, String action) {
        this.plugin = plugin;
        this.manager = manager;
        this.action = action;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!sender.hasPermission("sfpmenu.punish." + action)) {
            sender.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return;
        }
        if (!manager.isStorageAvailable()) {
            sender.sendMessage(plugin.getMessage("common.feature-disabled",
                    "<red>该功能当前未启用。</red>"));
            return;
        }

        switch (action) {
            case "check" -> check(sender, args);
            case "warn" -> warn(sender, args);
            case "kick" -> kick(sender, args);
            case "ban" -> timed(sender, args, PunishmentType.BAN);
            case "mute" -> timed(sender, args, PunishmentType.MUTE);
            case "unban" -> revoke(sender, args, PunishmentType.BAN);
            case "unmute" -> revoke(sender, args, PunishmentType.MUTE);
            default -> send(sender, render("common.unknown-subcommand",
                    "<red>未知子命令，输入 /sfp 查看用法。</red>", Map.of()));
        }
    }

    // ==================== 查询 ====================

    /** 按处罚 ID 或玩家名查询处罚历史，并顺带展示「当前生效」状态 */
    private void check(CommandSender sender, String[] args) {
        if (args.length == 0) {
            send(sender, render("punish.usage-check",
                    "<red>用法：/sfpcheck <处罚ID 或 玩家名></red>", Map.of()));
            return;
        }
        String keyword = args[0];
        List<PunishLog> logs = manager.query(keyword);
        if (logs.isEmpty()) {
            send(sender, render("punish.no-record",
                    "<gray>没有找到与 </gray><white>{keyword}</white><gray> 相关的处罚记录。</gray>",
                    Map.of("keyword", keyword)));
            UUID uuid = manager.resolveTarget(keyword);
            if (uuid != null && manager.activePunishments(uuid).isEmpty()) {
                send(sender, render("punish.no-active-punishment",
                        "<green>该玩家当前状态：无处罚。</green>", Map.of()));
            }
            return;
        }

        send(sender, render("punish.check-header",
                "<dark_gray>========== 处罚记录：</dark_gray><white>{keyword}</white>"
                        + "<dark_gray> ==========</dark_gray>", Map.of("keyword", keyword)));
        for (PunishLog log : logs) {
            send(sender, formatLog(log));
        }
        send(sender, render("punish.check-footer",
                "<dark_gray>共 {count} 条记录。</dark_gray>",
                Map.of("count", String.valueOf(logs.size()))));

        // 该玩家的「当前生效处罚」（已做惰性到期判定）
        UUID uuid = manager.resolveTarget(logs.get(0).playerName());
        if (uuid == null) return;
        List<Punishment> active = manager.activePunishments(uuid);
        if (active.isEmpty()) {
            send(sender, render("punish.check-active-none",
                    "<gray>当前生效处罚：</gray><green>无处罚</green>", Map.of()));
        } else {
            for (Punishment p : active) {
                Map<String, String> ph = new HashMap<>();
                ph.put("type", p.type().displayName());
                ph.put("duration", p.isPermanent() ? manager.getConfig().getPermanentText()
                        : "剩余 " + DurationParser.format(p.remainingMillis()));
                ph.put("id", p.id());
                send(sender, render("punish.check-active-entry",
                        "<gray>当前生效处罚：</gray><red>{type}</red><gray> </gray><white>{duration}</white>"
                                + "<gray>（编号 </gray><yellow>{id}</yellow><gray>）</gray>", ph));
            }
        }
    }

    /** 一条历史日志 → 一行展示（文案在 messages.yml 的 punish.log-line） */
    private Component formatLog(PunishLog log) {
        Map<String, String> ph = new HashMap<>();
        ph.put("time", new SimpleDateFormat(TIME_PATTERN).format(new Date(log.actionAt())));
        ph.put("type", log.type() == null ? "处罚" : log.type().displayName());
        ph.put("action", log.action() == null ? "处罚" : log.action().displayName());
        ph.put("id", log.punishmentId());
        ph.put("operator", log.operator() == null ? "控制台" : log.operator());
        ph.put("reason", log.reason() == null ? manager.getConfig().getDefaultReason() : log.reason());
        return render("punish.log-line",
                "<dark_gray>[{time}]</dark_gray> <white>{type}</white> <gray>{action}</gray>"
                        + " <dark_gray>编号</dark_gray><yellow>{id}</yellow>"
                        + " <dark_gray>· 操作者</dark_gray><white>{operator}</white>"
                        + " <dark_gray>· 原因</dark_gray><white>{reason}</white>", ph);
    }

    // ==================== 警告 / 踢出（仅在线） ====================

    private void warn(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, render("punish.usage-warn",
                    "<red>用法：/sfpwarn <在线玩家> <原因></red>", Map.of()));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            offline(sender, args[0]);
            return;
        }
        String reason = join(args, 1);
        String id = manager.warn(sender, target.getUniqueId(), target.getName(), reason);

        // 通知被警告者
        Map<String, String> toTarget = placeholders(sender, target.getName(), reason, id,
                PunishmentType.WARN, "-", "-");
        target.sendMessage(render("punish.warn-received",
                "<red>你收到了一个警告。</red><newline><gray>原因：</gray><white>{reason}</white>", toTarget));
        // 反馈执行者
        send(sender, render("punish.warn-applied",
                "<green>已警告 </green><white>{player}</white><green>，编号 </green><yellow>{id}</yellow>",
                toTarget));
    }

    private void kick(CommandSender sender, String[] args) {
        if (args.length < 2) {
            send(sender, render("punish.usage-kick",
                    "<red>用法：/sfpkick <在线玩家> <原因></red>", Map.of()));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            offline(sender, args[0]);
            return;
        }
        String reason = join(args, 1);
        String id = manager.kick(sender, target.getUniqueId(), target.getName(), reason);
        if (id == null) {
            offline(sender, args[0]);
            return;
        }
        send(sender, render("punish.kick-applied",
                "<green>已踢出 </green><white>{player}</white><green>，编号 </green><yellow>{id}</yellow>",
                placeholders(sender, target.getName(), reason, id, PunishmentType.KICK, "-", "-")));
    }

    // ==================== 封禁 / 禁言（可离线） ====================

    private void timed(CommandSender sender, String[] args, PunishmentType type) {
        boolean ban = type == PunishmentType.BAN;
        if (args.length < 3) {
            send(sender, render(ban ? "punish.usage-ban" : "punish.usage-mute",
                    ban ? "<red>用法：/sfpban <玩家> <时间> <原因></red>"
                            : "<red>用法：/sfpmute <玩家> <时间> <原因></red>", Map.of()));
            send(sender, render("punish.time-format-tip",
                    "<dark_gray>时间示例：7d（7天）、12h、1d2h30m、30m、perm（永久）</dark_gray>", Map.of()));
            return;
        }
        String targetName = args[0];
        String timeText = args[1];
        long duration = DurationParser.parse(timeText);
        if (duration == -2) {
            send(sender, render("punish.bad-time",
                    "<red>时间格式无法识别：</red><white>{time}</white>"
                            + "<dark_gray>（示例：7d / 12h / 1d2h30m / perm）</dark_gray>",
                    Map.of("time", timeText)));
            return;
        }
        UUID uuid = manager.resolveTarget(targetName);
        if (uuid == null) {
            unknownPlayer(sender, targetName);
            return;
        }
        String resolvedName = manager.getStore().findNameByName(targetName);
        if (resolvedName == null) resolvedName = targetName;
        String reason = join(args, 2);

        String id = type == PunishmentType.BAN
                ? manager.ban(sender, uuid, resolvedName, reason, duration)
                : manager.mute(sender, uuid, resolvedName, reason, duration);

        String durationText = duration == DurationParser.PERMANENT
                ? manager.getConfig().getPermanentText() : DurationParser.format(duration);
        Map<String, String> ph = placeholders(sender, resolvedName, reason, id, type,
                durationText, durationText);

        if (type == PunishmentType.BAN) {
            send(sender, render("punish.ban-applied",
                    "<green>已封禁 </green><white>{player}</white> <green>（</green><white>{duration}</white><green>）</green>"
                            + "<green>，编号 </green><yellow>{id}</yellow>", ph));
        } else {
            send(sender, render("punish.mute-applied",
                    "<green>已禁言 </green><white>{player}</white> <green>（</green><white>{duration}</white><green>）</green>"
                            + "<green>，编号 </green><yellow>{id}</yellow>", ph));
        }

        // 全服广播（punish.yml 可配开关与格式）
        var broadcast = manager.getConfig().broadcast(ph);
        if (broadcast != null) {
            Bukkit.getServer().broadcast(broadcast);
        }
    }

    // ==================== 解封 / 解禁（只从数据库查） ====================

    /**
     * 解封 / 解禁。
     * <p>
     * 目标来自数据库里「当前已封禁 / 已禁言」的名单（{@link PunishManager#bannedNames()} /
     * {@link PunishManager#mutedNames()}），<b>不使用在线玩家列表</b> —— 因为要解的往往是离线玩家。
     * 补全与执行都以数据库为准；名字解析不出 UUID 或没有生效处罚时明确告知。
     */
    private void revoke(CommandSender sender, String[] args, PunishmentType type) {
        boolean ban = type == PunishmentType.BAN;
        if (args.length == 0) {
            send(sender, render(ban ? "punish.usage-unban" : "punish.usage-unmute",
                    ban ? "<red>用法：/sfpunban <玩家名></red>" : "<red>用法：/sfpunmute <玩家名></red>",
                    Map.of()));
            send(sender, render("punish.revoke-tip",
                    "<dark_gray>目标来自数据库当前名单，输入时会自动补全。</dark_gray>", Map.of()));
            return;
        }
        String targetName = args[0];
        UUID uuid = manager.resolveTarget(targetName);
        if (uuid == null) {
            unknownPlayer(sender, targetName);
            return;
        }
        Punishment removed = manager.revoke(sender, uuid, type);
        if (removed == null) {
            send(sender, render(ban ? "punish.not-banned" : "punish.not-muted",
                    ban ? "<yellow>{player} 当前没有被封禁。</yellow>"
                            : "<yellow>{player} 当前没有被禁言。</yellow>",
                    Map.of("player", targetName)));
            return;
        }
        Map<String, String> ph = placeholders(sender, removed.playerName(), removed.reason(),
                removed.id(), type, "-", "-");
        if (ban) {
            send(sender, render("punish.unban-applied",
                    "<green>已解封 </green><white>{player}</white><green>，编号 </green><yellow>{id}</yellow>", ph));
        } else {
            send(sender, render("punish.unmute-applied",
                    "<green>已解除禁言 </green><white>{player}</white><green>，编号 </green><yellow>{id}</yellow>", ph));
        }
    }

    // ==================== 补全 ====================

    @Override
    public Collection<String> suggest(@NotNull CommandSourceStack source, @NotNull String[] args) {
        if (!(source.getSender() instanceof Player player)) return List.of();
        if (!player.hasPermission("sfpmenu.punish." + action)) return List.of();

        String prefix = args.length >= 1 ? args[args.length - 1].toLowerCase() : "";
        List<String> options = new ArrayList<>();

        switch (action) {
            case "warn", "kick" -> {
                if (args.length <= 1) {
                    for (Player p : Bukkit.getOnlinePlayers()) options.add(p.getName());
                }
            }
            case "ban", "mute" -> {
                if (args.length == 1) {
                    options.addAll(manager.knownPlayerNames());
                } else if (args.length == 2) {
                    options.addAll(List.of("1d", "7d", "30d", "12h", "1h", "30m", "perm"));
                }
            }
            case "unban" -> {
                if (args.length <= 1) options.addAll(manager.bannedNames());
            }
            case "unmute" -> {
                if (args.length <= 1) options.addAll(manager.mutedNames());
            }
            case "check" -> {
                if (args.length <= 1) options.addAll(manager.knownPlayerNames());
            }
            default -> {
            }
        }

        List<String> result = new ArrayList<>();
        for (String option : options) {
            if (PunishManager.matches(option, prefix)) result.add(option);
        }
        return result;
    }

    // ==================== 小工具 ====================

    /** 拼接剩余参数为一句原因（保留原始 MiniMessage 文本） */
    private static String join(String[] args, int from) {
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < args.length; i++) {
            if (sb.length() > 0) sb.append(' ');
            sb.append(args[i]);
        }
        return sb.toString();
    }

    /** 组装占位符（{player}{operator}{reason}{id}{type}{expire}{duration}） */
    private Map<String, String> placeholders(CommandSender sender, String player, String reason,
                                             String id, PunishmentType type,
                                             String expire, String duration) {
        Map<String, String> ph = new HashMap<>();
        ph.put("player", player);
        ph.put("operator", sender instanceof Player p ? p.getName() : "控制台");
        ph.put("reason", reason == null || reason.isBlank()
                ? manager.getConfig().getDefaultReason() : reason);
        ph.put("id", id == null ? "-" : id);
        ph.put("type", type.displayName());
        ph.put("expire", expire == null ? "-" : expire);
        ph.put("duration", duration == null ? "-" : duration);
        return ph;
    }

    /** 直接发送已构建好的组件 */
    private void send(CommandSender sender, Component component) {
        sender.sendMessage(component);
    }

    /** 从 messages.yml 取文案键，替换占位符后返回 Component */
    private Component render(String key, String fallback,
                             Map<String, String> placeholders) {
        return Messages.deserialize(Messages.apply(plugin.getRawMessage(key, fallback), placeholders));
    }

    private void offline(CommandSender sender, String name) {
        send(sender, render("punish.offline",
                "<red>玩家 </red><white>{player}</white><red> 不在线。</red>",
                Map.of("player", name)));
    }

    private void unknownPlayer(CommandSender sender, String name) {
        send(sender, render("punish.unknown-player",
                "<red>找不到玩家 </red><white>{player}</white>"
                        + "<red>（需该玩家至少进过一次服务器）。</red>",
                Map.of("player", name)));
    }
}
