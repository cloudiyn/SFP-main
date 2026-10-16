package cn.starfallplain.sfpmain.trashbin;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.Map;

/**
 * 垃圾桶界面交互监听器：
 * - 禁止放入物品（所有点击均取消）
 * - 点击物品格：取出该物品到玩家背包
 * - 点击上一页/下一页：翻页
 * - 禁止拖拽
 */
public class TrashBinListener implements Listener {

    private final SfpMain plugin;

    public TrashBinListener(SfpMain plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof TrashBinHolder holder)) return;
        // 所有交互一律取消（防止放入物品、拿走导航按钮、shift 移动等）
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        TrashBinManager manager = plugin.getTrashBinManager();
        if (manager == null) return;

        int slot = event.getRawSlot();
        // 只处理垃圾桶内的点击（0~53）
        if (slot < 0 || slot >= 54) return;

        // 翻页按钮
        if (slot == TrashBinHolder.SLOT_PREV) {
            if (holder.getPage() > 0) {
                reopen(plugin, player, manager, holder.getPage() - 1);
            }
            return;
        }
        if (slot == TrashBinHolder.SLOT_NEXT) {
            if (holder.getPage() < manager.getPageCount() - 1) {
                reopen(plugin, player, manager, holder.getPage() + 1);
            }
            return;
        }
        if (slot == TrashBinHolder.SLOT_BACK) {
            // 返回主菜单：先关掉本界面，延迟一 tick 再打开，避免在点击事件里直接换 GUI
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) MenuManager.openMainMenu(plugin, player);
            });
            return;
        }
        if (slot == TrashBinHolder.SLOT_INFO) {
            return; // 页码信息，无操作
        }
        // 导航区灰板
        if (slot >= holder.getPageSize()) {
            return;
        }

        // 物品格：取出
        int globalIndex = manager.toGlobalIndex(holder.getPage(), slot);
        ItemStack taken = manager.takeItem(globalIndex);
        if (taken == null) return;

        // 给玩家；背包满了丢脚下
        Map<Integer, ItemStack> overflow = player.getInventory().addItem(taken);
        for (ItemStack left : overflow.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), left);
        }

        if (manager.getConfig().isSaveImmediately()) {
            manager.save();
        }
        SoundUtil.play(player, player.getLocation(), manager.getConfig().getTakeSound(), 0.5f, 1.2f);

        // 刷新当前页
        int newPage = holder.getPage();
        if (newPage > 0 && newPage >= manager.getPageCount()) {
            newPage = manager.getPageCount() - 1;
        }
        reopen(plugin, player, manager, newPage);

        // 提示
        if (event.getClick() == ClickType.SHIFT_LEFT || event.getClick() == ClickType.SHIFT_RIGHT) {
            player.sendMessage(plugin.getMessage("trashbin.taken", "<green>已从垃圾桶取回物品。</green>"));
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TrashBinHolder) {
            event.setCancelled(true);
        }
    }

    /**
     * 延迟一 tick 再重开界面。
     * <p>
     * 与「返回主菜单」那条路径保持同一种做法：不在 {@code InventoryClickEvent} 回调里直接换 GUI，
     * 避免同一份代码里出现两种风格（直接换在 Paper 上也能跑，但不保证客户端表现一致）。
     */
    private void reopen(SfpMain plugin, Player player, TrashBinManager manager, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                TrashBinCommand.openPage(plugin, player, manager, page);
            }
        });
    }
}
