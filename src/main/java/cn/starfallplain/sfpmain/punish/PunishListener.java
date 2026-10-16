package cn.starfallplain.sfpmain.punish;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.PunishConfig;
import io.papermc.paper.event.player.AsyncChatEvent;
import org.bukkit.Bukkit;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 处罚系统的监听器：把「惰性到期判定」安放到必要的时机上。
 * <ul>
 *   <li>{@link PlayerJoinEvent} —— 补录玩家名映射；若存在生效封禁（且未过期）则拒绝登录；
 *       有生效禁言则提示。</li>
 *   <li>{@link AsyncChatEvent} —— 发言前检查禁言（此处是禁言真正生效的地方）。</li>
 *   <li>{@link PlayerQuitEvent} —— 刷新 last_seen。</li>
 * </ul>
 * 不注册任何定时任务 —— 过期的处罚只在这些时机（以及查询 / 解禁）被发现并清理。
 */
public class PunishListener implements Listener {

    private final SfpMain plugin;
    private final PunishManager manager;
    private final PunishConfig config;

    public PunishListener(SfpMain plugin, PunishManager manager) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = manager.getConfig();
    }

    /**
     * 进服：补录名字 → 查封禁（未过期则踢出）→ 查禁言（提示）。
     * <p>
     * 用 LOW 优先级：Bukkit 的事件顺序是 LOWEST → LOW → NORMAL → HIGH → HIGHEST → MONITOR，
     * 所以这里**早于**默认优先级（NORMAL）的插件执行 —— 先补录名字映射、先判定封禁，
     * 不必等别的插件处理完。若以后确实需要「在其它插件之后」再处理，应改用 HIGH / HIGHEST。
     * <p>
     * <b>代理环境下的踢人时机</b>：本插件刻意<b>不在事件里立刻 {@code kick()}</b>，
     * 而是延后到下一 tick 执行（见 {@link #scheduleBanKick}）。原因是 Velocity 这类代理
     * 会在「玩家尚未在某个子服站稳」时把被踢玩家当作「连接中断」重新投递到 {@code try}
     * 列表里的下一个子服（子服 A 踢出 → 代理转投大厅子服 B → B 再踢 → …）。
     * 客户端此时还没收到区块加载开始的包，于是会一直卡在「加载地形中」；
     * 而服务端这边连接已经关闭，看起来就是「玩家卡住、服务端已断开」。
     * 延后一 tick，让本次登录先在子服走完，代理就会把它当作一次正常的踢出，
     * 直接把玩家断开并显示踢出原因，而不是拿去重投下一个子服。
     */
    @EventHandler(priority = EventPriority.LOW)
    public void onJoin(PlayerJoinEvent event) {
        var player = event.getPlayer();
        // 1) 补录名字映射（离线目标选择依赖它）
        manager.recordJoin(player);

        // 2) 封禁检查（getActivePunishment 内部会做惰性到期判定）
        Punishment ban = manager.activeBan(player.getUniqueId());
        if (ban != null) {
            config.log("拦截已封禁玩家登录：" + player.getName() + "（处罚 " + ban.id() + "）");
            scheduleBanKick(player, ban);
            return;
        }

        // 3) 禁言提示（不禁登，只提醒）
        Punishment mute = manager.activeMute(player.getUniqueId());
        if (mute != null) {
            player.sendMessage(config.muteNotice(manager.placeholders(mute)));
        }
    }

    /**
     * 延后若干 tick 执行封禁踢人（原因见 {@link #onJoin} 的说明）。
     * <p>
     * 延迟 tick 数取 {@code punish.yml} 的 {@code kick-delay-ticks}（默认 1）；
     * 执行前会再确认玩家仍在线，避免玩家在延迟期间已自行退出时对失效实例调用 kick。
     */
    private void scheduleBanKick(org.bukkit.entity.Player player, Punishment ban) {
        var view = config.kickScreen(PunishmentType.BAN, manager.placeholders(ban));
        Bukkit.getScheduler().runTaskLater(plugin,
                () -> {
                    if (player.isOnline()) {
                        player.kick(view);
                    }
                },
                Math.max(1, config.getKickDelayTicks()));
    }

    /**
     * 发言：有生效禁言则拦下，并把禁言信息发回给发言者。
     * <p>
     * 用 Paper 的 {@link AsyncChatEvent}（旧的 AsyncPlayerChatEvent 已废弃）。
     * 该事件在<b>异步线程</b>触发，因此这里用纯内存的 {@code cachedMute}（不碰数据库连接），
     * 只做只读判定。缓存由主线程在施加 / 解除 / 到期时同步维护。
     */
    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        var player = event.getPlayer();
        Punishment mute = manager.cachedMute(player.getUniqueId());
        if (mute == null) return;
        event.setCancelled(true);
        player.sendMessage(config.muteNotice(manager.placeholders(mute)));
    }

    /** 退出：刷新 last_seen */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.recordQuit(event.getPlayer());
    }
}
