package cn.starfallplain.sfpmain.bot;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

/** 假人编辑器的容器标记，槽位内容由 BotInventoryManager 按实时库存生成。 */
public final class BotInventoryHolder implements InventoryHolder {
    private final BotInventoryManager manager;
    private final BotManager.Bot bot;
    private Inventory inventory;

    BotInventoryHolder(BotInventoryManager manager, BotManager.Bot bot) {
        this.manager = manager;
        this.bot = bot;
    }
    void inventory(Inventory inventory) { this.inventory = inventory; }
    BotInventoryManager manager() { return manager; }
    BotManager.Bot bot() { return bot; }
    @Override public Inventory getInventory() { return inventory; }
}
