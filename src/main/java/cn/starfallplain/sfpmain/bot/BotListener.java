package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * 假人相关监听：
 * <ul>
 *   <li><b>右键假人</b> → 打开其背包（对齐 minecraft-fakeplayer：右键与 {@code /fp invsee} 同效）</li>
 *   <li><b>假人离开服务端</b>（被踢 / 死亡后被服务端移除）→ 清掉指向它的「默认假人」选择</li>
 *   <li><b>执行者退出</b> → 清掉其「默认假人」选择（避免常驻内存）</li>
 * </ul>
 */
public class BotListener implements Listener {

    private final SfpMain plugin;

    public BotListener(SfpMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (!(event.getRightClicked() instanceof Player clicked)) return;
        BotManager manager = plugin.getBotManager();
        if (manager == null || !manager.getConfig().isEnabled()) return;
        if (!manager.isBot(clicked.getUniqueId())) return;

        // 假人不响应原版右键交互，直接拦掉并打开背包
        event.setCancelled(true);
        if (event.getHand() != EquipmentSlot.HAND) return;
        Player clicker = event.getPlayer();
        if (!clicker.hasPermission("sfpmenu.bot")) {
            plugin.getConfigManager().messages().send(clicker, "common.no-permission-with-node",
                    "<red>你没有权限使用这个功能（需要 {permission}）。</red>",
                    java.util.Map.of("permission", "sfpmenu.bot"));
            return;
        }
        BotManager.Bot bot = manager.byUuid(clicked.getUniqueId());
        if (bot != null) {
            manager.invsee(clicker, bot);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BotInventoryHolder holder) {
            holder.manager().click(event, holder);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof BotInventoryHolder holder) {
            holder.manager().drag(event);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player viewer
                && event.getInventory().getHolder() instanceof BotInventoryHolder holder) {
            holder.manager().closed(viewer, holder);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        BotManager manager = plugin.getBotManager();
        if (manager == null) return;
        manager.inventories().disconnected(event.getPlayer().getUniqueId());
        // 假人自己退出：清掉别人对它的选择
        if (manager.isBot(event.getPlayer().getUniqueId())) {
            manager.dropSelection(event.getPlayer().getUniqueId());
            return;
        }
        // 普通玩家退出：清掉他的选择
        manager.clearSelection(event.getPlayer().getUniqueId());
    }
}
