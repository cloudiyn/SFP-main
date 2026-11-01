package cn.starfallplain.sfpmain.teleport;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import cn.starfallplain.sfpmain.ui.TpaUiMode;
import cn.starfallplain.sfpmain.ui.UiPreferenceStore;
import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家间传送请求（{@code /tpa}、{@code /tpahere}、{@code /tpaccept}、{@code /tpdeny}）。
 * <p>
 * <b>请求是会话态，不入库</b>：玩家退出即失效，重启丢失可接受，放进 SQLite 反而要处理过期清理。
 * <p>
 * <b>响应界面双通道</b>（这是刻意的冗余设计）：
 * <ol>
 *   <li><b>弹窗界面</b> —— 用 Paper 的 Dialog API 弹出带「接受 / 拒绝」按钮的原生对话框
 *       （Minecraft 新版界面）。</li>
 *   <li><b>聊天 TUI</b> —— 同一条聊天消息里再给一对可点击的 {@code [接受] [拒绝]}，
 *       外加一行「手动输入 /tpaccept 名字」的提示。</li>
 * </ol>
 * 之所以两条都给：Dialog 是较新的客户端能力，装了 ViaVersion/ViaBackwards/ViaRewind 的服务器上
 * 可能有低版本客户端连入，弹窗渲染不出来时聊天里的按钮仍能点；两个按钮都失效时还能手打命令。
 * 三条路径最终都走同一套命令处理，行为完全一致。
 * <p>
 * 每位接收者同时只保留一个待处理请求（后来者覆盖先前的，并通知被覆盖的发起者），
 * 因此 {@code /tpaccept} 不带参数也能明确知道接受的是哪一个。
 */
public final class TpaManager {

    /** 传送方向：TO = 发起者去目标身边，HERE = 目标来发起者身边 */
    public enum Type { TO, HERE }

    /** 一笔待处理的请求（同时记下双方名字，便于离线后也能给出准确提示） */
    private record Pending(UUID from, String fromName, String targetName, Type type,
                           long expireAt, BukkitTask task) {
    }

    private final SfpMain plugin;
    private final TeleportConfig config;
    private final TeleportManager teleportManager;

    /** 接收者 UUID → 待处理请求 */
    private final Map<UUID, Pending> pending = new HashMap<>();
    /** 发起者 UUID → 请求冷却结束时间（毫秒） */
    private final Map<UUID, Long> requestCooldowns = new HashMap<>();

    public TpaManager(SfpMain plugin, TeleportConfig config, TeleportManager teleportManager) {
        this.plugin = plugin;
        this.config = config;
        this.teleportManager = teleportManager;
    }

    public boolean isEnabled() {
        return config.isTpaEnabled();
    }

    /** 当前待处理请求数量（供 /sfp status 与自检显示运行态） */
    public int pendingCount() {
        return pending.size();
    }

    /** 该玩家收到的请求是否来自指定名字（供 tpaccept 带参校验） */
    public boolean isRequestFrom(Player target, String fromName) {
        Pending p = pending.get(target.getUniqueId());
        return p != null && p.fromName().equalsIgnoreCase(fromName);
    }

    // ==================== 发起请求 ====================

    /**
     * 发起一笔传送请求，并同时推送「弹窗界面」与「聊天 TUI」。
     *
     * @return 是否成功发起
     */
    public boolean request(Player from, Player target, Type type) {
        if (!isEnabled()) {
            from.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return false;
        }
        if (from.getUniqueId().equals(target.getUniqueId())) {
            from.sendMessage(plugin.getMessage("tpa.self", "<red>不能向自己发起传送请求。</red>"));
            return false;
        }

        long now = System.currentTimeMillis();
        Long until = requestCooldowns.get(from.getUniqueId());
        if (until != null && until > now) {
            Map<String, String> ph = new HashMap<>();
            ph.put("seconds", String.valueOf((until - now + 999) / 1000));
            plugin.getConfigManager().messages().send(from, "tpa.cooldown",
                    "<red>传送请求冷却中，请等待 {seconds} 秒。</red>", ph);
            return false;
        }

        // 覆盖掉该接收者已有的请求（通知被覆盖的发起者）
        Pending old = pending.remove(target.getUniqueId());
        if (old != null) {
            if (old.task() != null) old.task().cancel();
            Player oldFrom = Bukkit.getPlayer(old.from());
            if (oldFrom != null && !oldFrom.getUniqueId().equals(from.getUniqueId())) {
                Map<String, String> ph = new HashMap<>();
                ph.put("target", target.getName());
                plugin.getConfigManager().messages().send(oldFrom, "tpa.replaced",
                        "<red>你发给 {target} 的传送请求已被新的请求取代。</red>", ph);
            }
        }

        int expire = config.getTpaExpireSeconds();
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();
        BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
            Pending current = pending.remove(targetId);
            if (current == null) return;   // 已被接受 / 拒绝
            Player f = Bukkit.getPlayer(current.from());
            if (f != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("target", current.targetName());
                plugin.getConfigManager().messages().send(f, "tpa.expired-from",
                        "<red>你发给 {target} 的传送请求已过期。</red>", ph);
            }
            Player t = Bukkit.getPlayer(targetId);
            if (t != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("from", current.fromName());
                plugin.getConfigManager().messages().send(t, "tpa.expired-target",
                        "<gray>{from} 的传送请求已过期。</gray>", ph);
            }
        }, expire * 20L);

        pending.put(targetId, new Pending(from.getUniqueId(), from.getName(), targetName,
                type, now + expire * 1000L, task));
        requestCooldowns.put(from.getUniqueId(), now + config.getTpaRequestCooldownSeconds() * 1000L);

        // 交付请求：按接收者自己的「传送回应界面」偏好（dialog / tui）
        deliver(target, from, type, expire);

        // 回执给发起者
        Map<String, String> ph = new HashMap<>();
        ph.put("target", target.getName());
        ph.put("seconds", String.valueOf(expire));
        plugin.getConfigManager().messages().send(from,
                type == Type.TO ? "tpa.sent-to" : "tpa.sent-here",
                type == Type.TO
                        ? "<green>已请求传送到 {target} 身边，等待对方响应（{seconds} 秒内有效）。</green>"
                        : "<green>已请求让 {target} 传送到你身边，等待对方响应（{seconds} 秒内有效）。</green>", ph);
        return true;
    }

    /** 弹出带「接受 / 拒绝」按钮的原生界面；两个按钮都只是执行对应命令，与聊天按钮同一条逻辑 */
    private void showDialog(Player target, Player from, Type type) {
        Messages messages = plugin.getConfigManager().messages();
        Component title = Messages.deserialize(messages.raw("tpa.dialog-title", "<!i><gold>传送请求</gold>"));
        Component body = Messages.deserialize(messages.raw(
                type == Type.TO ? "tpa.request-to" : "tpa.request-here",
                type == Type.TO
                        ? "<!i><yellow>{from}</yellow> <gray>请求传送到你身边</gray>"
                        : "<!i><yellow>{from}</yellow> <gray>请求把你传送到TA身边</gray>")
                .replace("{from}", from.getName()));

        ActionButton accept = ActionButton.builder(
                        Messages.deserialize(messages.raw("tpa.dialog-accept", "<!i><green>接受</green>")))
                .tooltip(Messages.deserialize(messages.raw("tpa.dialog-accept-hover",
                        "<!i><gray>同意这次传送</gray>")))
                .action(DialogAction.staticAction(ClickEvent.runCommand("/tpaccept " + from.getName())))
                .build();
        ActionButton deny = ActionButton.builder(
                        Messages.deserialize(messages.raw("tpa.dialog-deny", "<!i><red>拒绝</red>")))
                .tooltip(Messages.deserialize(messages.raw("tpa.dialog-deny-hover",
                        "<!i><gray>拒绝这次传送</gray>")))
                .action(DialogAction.staticAction(ClickEvent.runCommand("/tpdeny " + from.getName())))
                .build();

        // 第三个按钮：切换回应界面形式（dialog ↔ tui），偏好存 data/settings.db（旧版根目录自动迁移）
        ActionButton switchBtn = ActionButton.builder(
                        Messages.deserialize(messages.raw("tpa.dialog-switch", "<!i><yellow>切换界面</yellow>")))
                .tooltip(Messages.deserialize(messages.raw("tpa.dialog-switch-hover",
                        "<!i><gray>改用聊天里的按钮来回应</gray>")))
                .action(DialogAction.staticAction(ClickEvent.runCommand("/tpaui")))
                .build();

        Dialog dialog = Dialog.create(builder -> builder
                .empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.multiAction(List.of(accept, deny, switchBtn)).columns(3).build()));

        target.showDialog(dialog);
    }

    /** 聊天 TUI：可点击按钮 + 手动输入提示（弹窗不可用时的兜底） */
    private void sendChatUi(Player target, Player from, Type type, int expireSeconds) {
        Messages messages = plugin.getConfigManager().messages();
        Component text = Messages.deserialize(Messages.apply(messages.raw(
                type == Type.TO ? "tpa.request-to" : "tpa.request-here",
                type == Type.TO
                        ? "<!i><yellow>{from}</yellow> <gray>请求传送到你身边</gray>"
                        : "<!i><yellow>{from}</yellow> <gray>请求把你传送到TA身边</gray>"),
                Map.of("from", from.getName())));

        Component accept = Messages.deserialize(
                        messages.raw("tpa.tui-accept", "<!i><green>[接受]</green>"))
                .clickEvent(ClickEvent.runCommand("/tpaccept " + from.getName()))
                .hoverEvent(HoverEvent.showText(Messages.deserialize(
                        messages.raw("tpa.dialog-accept-hover", "<!i><gray>同意这次传送</gray>"))));
        Component deny = Messages.deserialize(
                        messages.raw("tpa.tui-deny", "<!i><red>[拒绝]</red>"))
                .clickEvent(ClickEvent.runCommand("/tpdeny " + from.getName()))
                .hoverEvent(HoverEvent.showText(Messages.deserialize(
                        messages.raw("tpa.dialog-deny-hover", "<!i><gray>拒绝这次传送</gray>"))));

        Component switchBtn = Messages.deserialize(
                        messages.raw("tpa.tui-switch", "<!i><yellow>[切换为弹窗]</yellow>"))
                .clickEvent(ClickEvent.runCommand("/tpaui"))
                .hoverEvent(HoverEvent.showText(Messages.deserialize(
                        messages.raw("tpa.tui-switch-hover", "<!i><gray>改用弹窗来回应</gray>"))));
        target.sendMessage(text.append(Component.space()).append(accept)
                .append(Component.space()).append(deny)
                .append(Component.space()).append(switchBtn));
        target.sendMessage(Messages.deserialize(Messages.apply(
                messages.raw("tpa.tui-tip",
                        "<!i><dark_gray>（上面的按钮点不动时，手动输入：/tpaccept {from} 或 /tpdeny {from}）</dark_gray>"),
                Map.of("from", from.getName(), "seconds", String.valueOf(expireSeconds)))));
    }

    /** 弹窗之外的兜底：只发一行「手动输入命令」提示（弹窗按钮点不动时仍能响应） */
    private void sendManualTip(Player target, Player from, int expireSeconds) {
        Messages messages = plugin.getConfigManager().messages();
        target.sendMessage(Messages.deserialize(Messages.apply(
                messages.raw("tpa.tui-tip",
                        "<!i><dark_gray>（弹窗按钮点不动时，手动输入：/tpaccept {from} 或 /tpdeny {from}）</dark_gray>"),
                Map.of("from", from.getName(), "seconds", String.valueOf(expireSeconds)))));
    }

    /** 按接收者的「传送回应界面」偏好交付请求 */
    private void deliver(Player target, Player from, Type type, int expireSeconds) {
        if (tpaUiMode(target) == TpaUiMode.DIALOG) {
            showDialog(target, from, type);
            sendManualTip(target, from, expireSeconds);
        } else {
            sendChatUi(target, from, type, expireSeconds);
        }
    }

    /** 读取玩家的传送回应界面偏好 */
    private TpaUiMode tpaUiMode(Player player) {
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        return store != null ? store.getTpaMode(player.getUniqueId()) : TpaUiMode.DIALOG;
    }

    /**
     * 切换「传送回应界面」形式（dialog ↔ tui），偏好存 data/settings.db（旧版根目录自动迁移）。
     * <p>
     * 若该玩家当前正好有一笔待处理的请求，会立刻用新形式重新发一遍
     * （弹窗里的「切换界面」按钮、聊天里的「[切换为弹窗]」都走这里）。
     */
    public void switchUi(Player player) {
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        if (store == null) return;

        TpaUiMode next = store.getTpaMode(player.getUniqueId()).toggle();
        store.setTpaMode(player.getUniqueId(), next);

        Map<String, String> ph = new HashMap<>();
        ph.put("mode", next == TpaUiMode.DIALOG ? "弹窗界面" : "聊天按钮界面");
        plugin.getConfigManager().messages().send(player, "tpa.ui-switched",
                "<green>传送请求的回应界面已切换为：{mode}。</green>", ph);

        // 有待处理的请求 → 立即用新形式重发
        Pending pending = this.pending.get(player.getUniqueId());
        if (pending == null) return;
        Player from = Bukkit.getPlayer(pending.from());
        if (from == null) return;
        int remaining = (int) Math.max(1,
                (pending.expireAt() - System.currentTimeMillis()) / 1000);
        deliver(player, from, pending.type(), remaining);
    }

    // ==================== 响应请求 ====================

    /**
     * 接受请求。{@code fromNameArg} 为空时接受当前唯一待处理的那笔。
     */
    public void accept(Player target, String fromNameArg) {
        Pending p = takePending(target, fromNameArg);
        if (p == null) return;

        Player from = Bukkit.getPlayer(p.from());
        if (from == null) {
            target.sendMessage(plugin.getMessage("tpa.offline", "<red>对方已离线，请求已取消。</red>"));
            return;
        }

        Map<String, String> ph = new HashMap<>();
        ph.put("from", p.fromName());
        ph.put("target", target.getName());
        plugin.getConfigManager().messages().send(target, "tpa.accepted-target",
                "<green>已接受 {from} 的传送请求。</green>", ph);
        plugin.getConfigManager().messages().send(from, "tpa.accepted-from",
                "<green>{target} 接受了你的请求，正在传送……</green>", ph);

        // 统一走 TeleportManager，自动带上安全落点、延迟传送与音效
        boolean ok;
        if (p.type() == Type.TO) {
            ok = teleportManager.teleport(from, StoredLocation.of(target.getLocation()));
            if (ok) teleportManager.applyTeleportCooldown(from, config.getTpaTeleportCooldownSeconds());
        } else {
            ok = teleportManager.teleport(target, StoredLocation.of(from.getLocation()));
            if (ok) teleportManager.applyTeleportCooldown(target, config.getTpaTeleportCooldownSeconds());
        }
        if (!ok) {
            target.sendMessage(plugin.getMessage("teleport.failed", "<red>传送失败。</red>"));
            from.sendMessage(plugin.getMessage("teleport.failed", "<red>传送失败。</red>"));
        }
    }

    /** 拒绝请求。{@code fromNameArg} 为空时拒绝当前唯一待处理的那笔。 */
    public void deny(Player target, String fromNameArg) {
        Pending p = takePending(target, fromNameArg);
        if (p == null) return;

        Map<String, String> ph = new HashMap<>();
        ph.put("from", p.fromName());
        ph.put("target", target.getName());
        plugin.getConfigManager().messages().send(target, "tpa.denied-target",
                "<gray>已拒绝 {from} 的传送请求。</gray>", ph);
        Player from = Bukkit.getPlayer(p.from());
        if (from != null) {
            plugin.getConfigManager().messages().send(from, "tpa.denied-from",
                    "<red>{target} 拒绝了你的传送请求。</red>", ph);
        }
    }

    /** 取出并移除待处理请求；没有或名字对不上时给出提示并返回 null */
    private Pending takePending(Player target, String fromNameArg) {
        Pending p = pending.get(target.getUniqueId());
        if (p == null) {
            target.sendMessage(plugin.getMessage("tpa.no-request",
                    "<red>没有待处理的传送请求（可能已被处理或已过期）。</red>"));
            return null;
        }
        if (fromNameArg != null && !fromNameArg.isBlank() && !p.fromName().equalsIgnoreCase(fromNameArg)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", fromNameArg);
            plugin.getConfigManager().messages().send(target, "tpa.not-found",
                    "<red>没有来自「{name}」的待处理请求。</red>", ph);
            return null;
        }
        pending.remove(target.getUniqueId());
        if (p.task() != null) p.task().cancel();
        return p;
    }

    /** 待处理的发起者名字（供命令补全与提示） */
    public List<String> pendingFromNames(Player target) {
        List<String> names = new ArrayList<>();
        Pending p = pending.get(target.getUniqueId());
        if (p != null) names.add(p.fromName());
        return names;
    }

    // ==================== 清理 ====================

    /** 玩家退出：清掉与他相关的请求（他是发起者或接收者） */
    public void onQuit(Player player) {
        UUID id = player.getUniqueId();

        Pending asTarget = pending.remove(id);
        if (asTarget != null) {
            if (asTarget.task() != null) asTarget.task().cancel();
            Player from = Bukkit.getPlayer(asTarget.from());
            if (from != null) {
                Map<String, String> ph = new HashMap<>();
                ph.put("target", player.getName());
                plugin.getConfigManager().messages().send(from, "tpa.expired-from",
                        "<red>你发给 {target} 的传送请求已过期。</red>", ph);
            }
        }

        // 他是某个请求的发起者 → 通知接收者请求作废
        for (Map.Entry<UUID, Pending> e : new ArrayList<>(pending.entrySet())) {
            if (e.getValue().from().equals(id)) {
                pending.remove(e.getKey());
                if (e.getValue().task() != null) e.getValue().task().cancel();
                Player t = Bukkit.getPlayer(e.getKey());
                if (t != null) {
                    Map<String, String> ph = new HashMap<>();
                    ph.put("from", player.getName());
                    plugin.getConfigManager().messages().send(t, "tpa.from-offline",
                            "<gray>{from} 已离线，传送请求已取消。</gray>", ph);
                }
            }
        }
        requestCooldowns.remove(id);
    }

    /** 插件卸载：取消全部过期任务 */
    public void shutdown() {
        for (Pending p : pending.values()) {
            if (p.task() != null) p.task().cancel();
        }
        pending.clear();
        requestCooldowns.clear();
    }
}
