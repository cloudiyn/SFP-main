package cn.starfallplain.sfpmain.teleport.gui;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.TpaManager;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 传送列表界面交互监听器：
 * <ul>
 *   <li>左键条目 —— 传送</li>
 *   <li>右键条目 —— 删除（家直接删；传送点需 delwarp 权限）</li>
 *   <li>翻页按钮 —— 上下页</li>
 *   <li>禁止一切物品拖放</li>
 * </ul>
 */
public class TeleportGuiListener implements Listener {

    private final SfpMain plugin;
    private final TeleportManager manager;

    public TeleportGuiListener(SfpMain plugin, TeleportManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        // 玩家传送目标界面（内容格是玩家头颅，语义与列表不同，单独处理）
        if (event.getInventory().getHolder() instanceof TpaTargetHolder tpaHolder) {
            event.setCancelled(true);
            handleTpaTarget(event, tpaHolder);
            return;
        }

        if (!(event.getInventory().getHolder() instanceof TeleportListHolder holder)) return;
        event.setCancelled(true);

        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        TeleportConfig config = manager.getConfig();

        // 翻页
        if (slot == TeleportListHolder.SLOT_PREV) {
            if (holder.getPage() > 0) {
                reopenList(player, holder.getListType(), holder.getPage() - 1);
            }
            return;
        }
        if (slot == TeleportListHolder.SLOT_NEXT) {
            int next = holder.getPage() + 1;
            // 越界时 open 内部会自动夹紧
            reopenList(player, holder.getListType(), next);
            return;
        }
        if (slot == TeleportListHolder.SLOT_BACK) {
            // 返回主菜单：先关掉本界面，延迟一 tick 再打开，避免在点击事件里直接换 GUI
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) MenuManager.openMainMenu(plugin, player);
            });
            return;
        }
        if (slot == TeleportListHolder.SLOT_INFO) return;
        if (slot >= holder.getPageSize()) return; // 导航区灰板

        String name = holder.getEntryAt(slot);
        if (name == null) return;

        boolean rightClick = event.getClick() == ClickType.RIGHT
                || event.getClick() == ClickType.SHIFT_RIGHT;

        if (holder.getListType() == TeleportListHolder.ListType.HOME) {
            if (rightClick) {
                handleHomeDelete(player, name);
            } else {
                handleHomeTeleport(player, name);
            }
        } else {
            if (rightClick) {
                handleWarpDelete(player, name);
            } else {
                handleWarpTeleport(player, name);
            }
        }
    }

    // ==================== 家 ====================

    private void handleHomeTeleport(Player player, String name) {
        if (!manager.getConfig().isHomeEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        manager.teleportHome(player, name);
    }

    private void handleHomeDelete(Player player, String name) {
        if (!manager.getHomeStore().delete(player.getUniqueId(), name)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "home.not-found",
                    "<red>不存在名为「{name}」的家。</red>", ph);
            return;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "home.del-success",
                "<green>已删除家「{name}」。</green>", ph);
        // 刷新列表（回到首页）
        reopenList(player, TeleportListHolder.ListType.HOME, 0);
    }

    // ==================== 传送点 ====================

    private void handleWarpTeleport(Player player, String name) {
        if (!manager.getConfig().isWarpEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return;
        }
        manager.teleportWarp(player, name);
    }

    private void handleWarpDelete(Player player, String name) {
        String permission = manager.getConfig().getWarpDeletePermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return;
        }
        if (!manager.getWarpStore().delete(name)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "warp.not-found",
                    "<red>不存在名为「{name}」的传送点。</red>", ph);
            return;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "warp.del-success",
                "<green>已删除传送点「{name}」。</green>", ph);
        reopenList(player, TeleportListHolder.ListType.WARP, 0);
    }

    // ==================== 工具 ====================

    /**
     * 延迟一 tick 再重开列表界面。
     * <p>
     * 与「返回主菜单」那条路径保持同一种做法：不在 {@code InventoryClickEvent} 回调里直接换 GUI。
     * 传送到目标界面同理（{@link #reopenTpaTarget}）。
     */
    private void reopenList(Player player, TeleportListHolder.ListType type, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                TeleportListGui.open(plugin, player, manager, type, page);
            }
        });
    }

    /** 延迟一 tick 重开「选择传送目标」界面（理由同 {@link #reopenList}） */
    private void reopenTpaTarget(Player player, int page) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (player.isOnline()) {
                TpaTargetGui.open(plugin, player, page);
            }
        });
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof TeleportListHolder
                || event.getInventory().getHolder() instanceof TpaTargetHolder) {
            event.setCancelled(true);
        }
    }

    // ==================== 玩家传送目标界面 ====================

    /** 左键 = 请求传送到该玩家身边，右键 = 请求该玩家传送到自己身边 */
    private void handleTpaTarget(InventoryClickEvent event, TpaTargetHolder holder) {
        if (!(event.getWhoClicked() instanceof Player player)) return;
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= 54) return;

        if (slot == TpaTargetHolder.SLOT_PREV) {
            if (holder.getPage() > 0) {
                reopenTpaTarget(player, holder.getPage() - 1);
            }
            return;
        }
        if (slot == TpaTargetHolder.SLOT_NEXT) {
            // 越界时 open 内部会夹紧
            reopenTpaTarget(player, holder.getPage() + 1);
            return;
        }
        if (slot == TpaTargetHolder.SLOT_BACK) {
            player.closeInventory();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline()) MenuManager.openMainMenu(plugin, player);
            });
            return;
        }
        if (slot == TpaTargetHolder.SLOT_INFO) return;
        if (slot >= holder.getPageSize()) return;   // 导航区灰板

        TpaManager tpaManager = plugin.getTpaManager();
        if (tpaManager == null) return;

        UUID targetId = holder.getEntryAt(slot);
        if (targetId == null) return;
        Player target = Bukkit.getPlayer(targetId);
        if (target == null) {
            player.sendMessage(plugin.getMessage("tpa.target-offline",
                    "<red>该玩家已离线。</red>"));
            reopenTpaTarget(player, holder.getPage());   // 刷新列表
            return;
        }

        boolean rightClick = event.getClick() == ClickType.RIGHT
                || event.getClick() == ClickType.SHIFT_RIGHT;
        if (tpaManager.request(player, target, rightClick ? TpaManager.Type.HERE : TpaManager.Type.TO)) {
            player.closeInventory();   // 发起成功就回到游戏，等对方在弹窗/聊天里响应
        }
    }
}
