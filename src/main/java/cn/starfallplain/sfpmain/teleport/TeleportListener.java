package cn.starfallplain.sfpmain.teleport;

import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * 传送相关事件监听：为 /back 记录玩家位置，并处理延迟传送的打断。
 * <p>
 * 记录时机（均由 teleport.yml 的 back.* 开关控制）：
 * <ul>
 *   <li>死亡时 —— 记录死亡点，/back 可回到死亡处</li>
 *   <li>传送前 —— 记录传送前位置，便于回退</li>
 *   <li>切换世界时 —— 记录切换前位置</li>
 *   <li>退出时 —— 记录当前所在位置</li>
 * </ul>
 * 此外，玩家在延迟传送等待期间移动（跨越方块）或被其他传送带走时，
 * 会打断等待中的传送（见 teleport.yml 的 teleport.delay-seconds）。
 */
public class TeleportListener implements Listener {

    private final TeleportManager manager;
    private final TeleportConfig config;

    public TeleportListener(TeleportManager manager, TeleportConfig config) {
        this.manager = manager;
        this.config = config;
    }

    /** 死亡点：记录死亡位置作为 /back 目标 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDeath(PlayerDeathEvent event) {
        if (!config.isBackRecordDeath()) return;
        manager.recordLastLocation(event.getEntity());
    }

    /**
     * 移动打断：等待期间跨越方块坐标即取消传送。
     * 仅比较方块坐标，避免转头 / 微小位移造成误打断。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        var to = event.getTo();
        var from = event.getFrom();
        // 同一方块内（转头、微调）直接跳过，减少无谓查询
        if (from.getBlockX() == to.getBlockX()
                && from.getBlockY() == to.getBlockY()
                && from.getBlockZ() == to.getBlockZ()) {
            return;
        }
        manager.handleMove(event.getPlayer(), to);
    }

    /**
     * 传送前：记录传送前的位置；若玩家自己另有传送，打断等待中的延迟传送。
     * <p>
     * 切换世界同样会触发本事件（cause 为 NETHER_PORTAL / PLUGIN 等），
     * 且 {@code getFrom()} 就是真实的旧世界旧坐标，因此跨世界记录也在这里完成，
     * 不再单独监听 PlayerChangedWorldEvent（后者拿不到旧坐标，且在本插件
     * 自身传送后会覆盖正确的 /back 记录）。
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        var player = event.getPlayer();
        boolean internal = event.getCause() == PlayerTeleportEvent.TeleportCause.PLUGIN
                && manager.isInternalTeleport(player);
        if (!internal) {
            // 非本插件发起的传送，打断等待中的延迟传送
            manager.cancelPending(player, true);
        }
        // 本插件自身发起的传送已在 executeTeleport() 中记录，避免重复/错误覆盖
        if (internal) return;

        var from = event.getFrom();
        if (from.getWorld() == null) return;

        var to = event.getTo();
        boolean crossWorld = to != null && to.getWorld() != null
                && !from.getWorld().getName().equals(to.getWorld().getName());

        if (crossWorld) {
            if (config.isBackRecordWorldChange()) {
                manager.recordSpecificLocation(player, from);
            }
        } else if (config.isBackRecordTeleport()) {
            manager.recordSpecificLocation(player, from);
        }
    }

    /** 退出：清理等待中的传送与一次性运行态，并按配置记录当前位置 */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        manager.cancelPending(event.getPlayer(), false);
        manager.clearTransientState(event.getPlayer());
        manager.recordOnQuit(event.getPlayer());
    }

    /** 受伤打断：等待期间受到任何伤害都取消传送 */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;
        manager.cancelOnDamage(player);
    }
}
