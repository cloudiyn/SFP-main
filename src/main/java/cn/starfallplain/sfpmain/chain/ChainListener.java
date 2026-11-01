package cn.starfallplain.sfpmain.chain;

import cn.starfallplain.sfpmain.SfpMain;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;

/**
 * 连锁挖矿的触发入口。
 *
 * <p>用 {@link EventPriority#NORMAL}（不是 MONITOR）有两个原因：
 * <ul>
 *   <li><b>要能被取消</b> —— 玩家连锁挖的每一块都自己发 {@code BlockBreakEvent}，
 *       如果本监听器在最前面触发连锁，被领地保护取消掉的方块已经算进结果了。
 *       放在 NORMAL 让保护类插件（通常 HIGH/MONITOR）有机会先取消。</li>
 *   <li><b>要看到最终方块</b> —— MONITOR 阶段方块可能已被别的逻辑改成空气了。</li>
 * </ul>
 * 连锁阶段会对每个方块<b>递归</b>调用一次本监听器（因为它自己会发事件），
 * 因此必须用线程局部标志防止无限递归 —— 见 {@link #handling}。
 */
public class ChainListener implements Listener {

    private final SfpMain plugin;

    /** 连锁进行中的重入保护：连锁内部发的事件不再触发新的连锁 */
    private final ThreadLocal<Boolean> handling = ThreadLocal.withInitial(() -> false);

    public ChainListener(SfpMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBreak(BlockBreakEvent event) {
        // 连锁内部我们自己发的 BlockBreakEvent：不递归触发
        if (handling.get()) return;

        ChainManager manager = plugin.getChainManager();
        if (manager == null) return;

        handling.set(true);
        try {
            manager.handleBreak(event);
        } catch (Throwable t) {
            // 连锁失败绝不能影响玩家正常挖矿
            plugin.getLogger().warning("连锁挖矿执行异常：" + t);
        } finally {
            handling.set(false);
        }
    }
}