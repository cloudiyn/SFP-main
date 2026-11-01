package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 实时槽位映射编辑器。展示物只是副本，所有事件先取消，再从当前实体读取并逐槽提交。
 * 不在关闭时回写背包，因此多查看者、假人拾取/补货/使用/丢弃不会覆盖彼此的修改。
 * 所有调用均在 Bukkit 主线程执行；盔甲不在本编辑器范围内。
 */
public final class BotInventoryManager {
    /** 底排第 0、1 格：主手别名与副手实槽，标签按钮在正上方 36/37。 */
    static final int MAIN_HAND = 45;
    static final int OFF_HAND = 46;
    private final SfpMain plugin;
    private final BotManager manager;
    private final Map<UUID, BotInventorySession> sessions = new HashMap<>();
    private final Map<UUID, Object> pending = new HashMap<>();
    private BukkitTask refreshTask;
    private boolean stopped;

    BotInventoryManager(SfpMain plugin, BotManager manager) {
        this.plugin = plugin;
        this.manager = manager;
    }

    private Messages messages() { return plugin.getConfigManager().messages(); }

    public void open(Player viewer, BotManager.Bot bot) {
        if (stopped || !viewer.isOnline() || !viewer.hasPermission("sfpmenu.bot")) return;
        Object token = new Object();
        pending.put(viewer.getUniqueId(), token);
        Inventory previousTop = viewer.getOpenInventory().getTopInventory();
        // 命令与右键共用延迟入口，避免在库存事件回调内切换界面。
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!pending.remove(viewer.getUniqueId(), token) || stopped || !viewer.isOnline()) return;
            if (!viewer.getOpenInventory().getTopInventory().equals(previousTop)
                    || !viewer.hasPermission("sfpmenu.bot")) return;
            Player target = manager.online(bot);
            if (manager.byUuid(bot.uuid) != bot || !manager.getConfig().isEnabled()
                    || target == null || !target.isOnline() || target.isDead() || target == viewer) {
                messages().send(viewer, "bot.inventory-unavailable", "", Map.of("name", bot.name));
                return;
            }
            BotInventoryHolder holder = new BotInventoryHolder(this, bot);
            Inventory inventory = Bukkit.createInventory(holder, 54, Messages.deserialize(Messages.apply(
                    messages().raw("bot.inventory-title", ""), Map.of("name", bot.name))));
            holder.inventory(inventory);
            decorate(holder);
            BotInventorySession session = new BotInventorySession(viewer, target, holder);
            refresh(session);
            viewer.openInventory(inventory);
            // 其他插件可能取消打开或立即切换界面，不保留未实际打开的会话。
            if (viewer.getOpenInventory().getTopInventory().getHolder() != holder) return;
            sessions.put(viewer.getUniqueId(), session);
            if (refreshTask == null) refreshTask = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
        });
    }

    private void decorate(BotInventoryHolder holder) {
        Inventory inv = holder.getInventory();
        for (int slot = 36; slot < inv.getSize(); slot++) {
            inv.setItem(slot, button(Material.GRAY_STAINED_GLASS_PANE, "bot.inventory-locked"));
        }
        // 36/37 是正对 45/46 的说明牌，45/46 由 refresh() 写入真实物品。
        inv.setItem(36, button(Material.IRON_SWORD, "bot.inventory-main-hand"));
        inv.setItem(37, button(Material.SHIELD, "bot.inventory-off-hand"));
        inv.setItem(44, button(Material.CHEST, "bot.inventory-storage"));
        inv.setItem(49, button(Material.PAPER, "bot.inventory-help"));
        inv.setItem(53, button(Material.BARRIER, "bot.inventory-close"));
    }

    private ItemStack button(Material material, String key) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(messages().component(key, ""));
        List<String> lore = messages().rawList(key + "-lore");
        if (!lore.isEmpty()) meta.lore(lore.stream().map(Messages::deserialize).toList());
        item.setItemMeta(meta);
        return item;
    }

    /** 45 号主手别名映射到当前快捷栏槽，46 号副手映射到 PlayerInventory 的 40。 */
    static int mappedSlot(int rawSlot, PlayerInventory inventory) {
        if (rawSlot >= 0 && rawSlot < 36) return rawSlot;
        if (rawSlot == MAIN_HAND) return inventory.getHeldItemSlot();
        if (rawSlot == OFF_HAND) return 40;
        return -1;
    }

    private boolean valid(BotInventorySession session) {
        return !session.closing && !stopped && manager.getConfig().isEnabled()
                && session.viewer.isOnline() && session.viewer.hasPermission("sfpmenu.bot")
                && manager.byUuid(session.holder.bot().uuid) == session.holder.bot()
                && manager.online(session.holder.bot()) == session.target
                && session.target.isOnline() && !session.target.isDead()
                && session.viewer.getOpenInventory().getTopInventory().getHolder() == session.holder;
    }

    private void tick() {
        for (BotInventorySession session : new ArrayList<>(sessions.values())) {
            if (!valid(session)) invalidate(session);
            else refresh(session);
        }
        if (sessions.isEmpty() && refreshTask != null) {
            refreshTask.cancel();
            refreshTask = null;
        }
    }

    private void refresh(BotInventorySession session) {
        Inventory display = session.holder.getInventory();
        PlayerInventory live = session.target.getInventory();
        for (int slot = 0; slot < 36; slot++) display.setItem(slot, copy(live.getItem(slot)));
        display.setItem(MAIN_HAND, copy(live.getItemInMainHand()));
        display.setItem(OFF_HAND, copy(live.getItemInOffHand()));
    }

    public void click(InventoryClickEvent event, BotInventoryHolder holder) {
        // 包括底部背包、双击收集、创造模式克隆、窗口外丢弃，绝不让原版搬动展示副本。
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player viewer)) return;
        BotInventorySession session = sessions.get(viewer.getUniqueId());
        if (session == null || session.holder != holder || !valid(session)) {
            if (session != null) invalidate(session);
            return;
        }
        if (event.getRawSlot() == 53) {
            session.closing = true;
            closeLater(session);
            return;
        }
        // 把物品丢到窗口外（rawSlot=-999）：编辑器不回写、也不静默吞掉，明确提示不支持
        if (event.getRawSlot() < 0) {
            messages().send(viewer, "bot.inventory-unsupported", "", Map.of());
            return;
        }
        PlayerInventory live = session.target.getInventory();
        boolean top = event.getRawSlot() >= 0 && event.getRawSlot() < holder.getInventory().getSize();
        int slot = top ? mappedSlot(event.getRawSlot(), live) : event.getSlot();
        if (slot < 0 || (!top && (!viewer.getInventory().equals(event.getClickedInventory()) || slot >= 36))) return;
        Inventory source = top ? live : viewer.getInventory();
        ItemStack current = copy(source.getItem(slot));
        // 客户端看到的旧槽位已被动作或别的查看者修改时，刷新后再点，不拿新物品替旧物品操作。
        if (top && !same(current, holder.getInventory().getItem(event.getRawSlot()))) {
            refresh(session);
            return;
        }
        ClickType click = event.getClick();
        if (click == ClickType.LEFT || click == ClickType.RIGHT) {
            normalClick(viewer, source, slot, click == ClickType.RIGHT);
        } else if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
            shift(source, slot, top ? viewer.getInventory() : live);
        } else if (click == ClickType.NUMBER_KEY || click == ClickType.SWAP_OFFHAND) {
            // 数字键/F 键只用于交换假人上方槽位；底部玩家背包始终保持只读。
            if (!top) return;
            int hotbar = click == ClickType.SWAP_OFFHAND ? 40 : event.getHotbarButton();
            if (hotbar >= 0 && hotbar <= 40 && (hotbar < 9 || hotbar == 40)) {
                swap(source, slot, viewer.getInventory(), hotbar);
            }
        } else {
            // 拖拽/双击/克隆/丢弃被明确禁用，玩家可先取回到自身背包再在关闭界面后丢弃。
            messages().send(viewer, "bot.inventory-unsupported", "", Map.of());
        }
        for (BotInventorySession other : sessions.values()) {
            if (other.target == session.target && valid(other)) refresh(other);
        }
        // DENY 的客户端回滚包可能晚于槽位修改，下一 tick 发送权威库存与光标。
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (viewer.isOnline()) viewer.updateInventory();
        });
    }

    private static void normalClick(Player viewer, Inventory inventory, int slot, boolean right) {
        ItemStack current = copy(inventory.getItem(slot));
        ItemStack cursor = copy(viewer.getItemOnCursor());
        if (cursor == null) {
            if (current == null) return;
            int amount = right ? (current.getAmount() + 1) / 2 : current.getAmount();
            viewer.setItemOnCursor(portion(current, amount));
            inventory.setItem(slot, portion(current, current.getAmount() - amount));
        } else if (current == null || current.isSimilar(cursor)) {
            int capacity = Math.min(inventory.getMaxStackSize(), cursor.getMaxStackSize());
            int old = current == null ? 0 : current.getAmount();
            int amount = Math.min(right ? 1 : cursor.getAmount(), Math.max(0, capacity - old));
            if (amount == 0) return;
            inventory.setItem(slot, portion(cursor, old + amount));
            viewer.setItemOnCursor(portion(cursor, cursor.getAmount() - amount));
        } else if (cursor.getAmount() <= Math.min(inventory.getMaxStackSize(), cursor.getMaxStackSize())) {
            inventory.setItem(slot, cursor);
            viewer.setItemOnCursor(current);
        }
    }

    /** Shift 只操作储物格，副手与主手别名不会成为两个重复目标。 */
    private static void shift(Inventory source, int slot, PlayerInventory destination) {
        ItemStack item = copy(source.getItem(slot));
        if (item == null) return;
        int left = item.getAmount();
        for (int pass = 0; pass < 2 && left > 0; pass++) {
            for (int target = 0; target < 36 && left > 0; target++) {
                ItemStack existing = copy(destination.getItem(target));
                if (pass == 0 ? existing == null || !existing.isSimilar(item) : existing != null) continue;
                int amount = existing == null ? 0 : existing.getAmount();
                int moved = Math.min(left, Math.max(0,
                        Math.min(destination.getMaxStackSize(), item.getMaxStackSize()) - amount));
                if (moved == 0) continue;
                destination.setItem(target, portion(item, amount + moved));
                left -= moved;
            }
        }
        source.setItem(slot, portion(item, left));
    }

    private static void swap(Inventory source, int slot, PlayerInventory inventory, int otherSlot) {
        if (source.equals(inventory) && slot == otherSlot) return;
        ItemStack first = copy(source.getItem(slot));
        ItemStack second = copy(inventory.getItem(otherSlot));
        if (second != null && second.getAmount() > Math.min(source.getMaxStackSize(), second.getMaxStackSize())) return;
        if (first != null && first.getAmount() > Math.min(inventory.getMaxStackSize(), first.getMaxStackSize())) return;
        source.setItem(slot, second);
        inventory.setItem(otherSlot, first);
    }

    public void drag(InventoryDragEvent event) {
        event.setCancelled(true);
        if (event.getWhoClicked() instanceof Player viewer) {
            messages().send(viewer, "bot.inventory-unsupported", "", Map.of());
        }
    }

    public void closed(Player viewer, BotInventoryHolder holder) {
        BotInventorySession session = sessions.get(viewer.getUniqueId());
        if (session != null && session.holder == holder) sessions.remove(viewer.getUniqueId());
        // 展示容器不持有物品所有权，关闭时绝不回写；光标由原版关闭流程归还。
    }

    public void disconnected(UUID uuid) {
        pending.remove(uuid);
        sessions.remove(uuid);
        for (BotInventorySession session : new ArrayList<>(sessions.values())) {
            if (session.target.getUniqueId().equals(uuid)) invalidate(session);
        }
    }

    private void invalidate(BotInventorySession session) {
        if (session.closing) return;
        session.closing = true;
        messages().send(session.viewer, "bot.inventory-unavailable", "", Map.of("name", session.holder.bot().name));
        closeLater(session);
    }

    private void closeLater(BotInventorySession session) {
        Bukkit.getScheduler().runTask(plugin, () -> {
            sessions.remove(session.viewer.getUniqueId(), session);
            if (session.viewer.isOnline()
                    && session.viewer.getOpenInventory().getTopInventory().getHolder() == session.holder) {
                session.viewer.closeInventory();
            }
        });
    }

    public void shutdown() {
        stopped = true;
        pending.clear();
        if (refreshTask != null) { refreshTask.cancel(); refreshTask = null; }
        for (BotInventorySession session : new ArrayList<>(sessions.values())) {
            if (session.viewer.getOpenInventory().getTopInventory().getHolder() == session.holder) {
                session.viewer.closeInventory();
            }
        }
        sessions.clear();
    }

    private static ItemStack copy(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0 ? null : item.clone();
    }
    private static ItemStack portion(ItemStack item, int amount) {
        if (amount <= 0) return null;
        ItemStack result = item.clone();
        result.setAmount(amount);
        return result;
    }
    private static boolean same(ItemStack first, ItemStack second) {
        return java.util.Objects.equals(first, copy(second));
    }
}
