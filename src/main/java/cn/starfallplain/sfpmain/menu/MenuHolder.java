package cn.starfallplain.sfpmain.menu;

import cn.starfallplain.sfpmain.config.module.MenuConfig;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.Collections;
import java.util.Map;

/**
 * 菜单 GUI 的 Holder：携带菜单类型与已解析好的槽位动作映射。
 * <p>
 * slotActions 由 MenuManager 在构建界面时写入，值为动作描述串：
 * <ul>
 *   <li>{@code cmd:&lt;命令&gt;} —— 点击执行命令（如 cmd:dom）</li>
 *   <li>{@code acttrashbin} —— 内置动作：打开垃圾桶</li>
 *   <li>{@code acthome} —— 内置动作：打开家列表</li>
 *   <li>{@code actwarp} —— 内置动作：打开传送点列表</li>
 *   <li>{@code actback} —— 内置动作：执行返回</li>
 *   <li>{@code acttpa} —— 内置动作：打开玩家传送目标选择界面</li>
 *   <li>{@code actrtp} —— 内置动作：执行随机传送</li>
 *   <li>{@code actelytra} —— 内置动作：消耗钻石兑换鞘翅</li>
 * </ul>
 */
public class MenuHolder implements InventoryHolder {

    public enum MenuType { MAIN }

    // 内置动作标记
    public static final String ACTION_TRASHBIN = "acttrashbin";
    public static final String ACTION_HOME = "acthome";
    public static final String ACTION_WARP = "actwarp";
    public static final String ACTION_BACK = "actback";
    public static final String ACTION_TPA = "acttpa";
    public static final String ACTION_RTP = "actrtp";
    /** 内置动作：消耗钻石兑换鞘翅（消耗数量由 menu.yml 的 elytra-exchange.cost 配置） */
    public static final String ACTION_ELYTRA = "actelytra";
    public static final String ACTION_TOGGLE_UI = "acttoggleui";
    public static final String CMD_PREFIX = "cmd:";

    private Inventory inventory;
    private final MenuType menuType;
    private final Map<Integer, String> slotActions;
    private final MenuConfig menuConfig;

    public MenuHolder(MenuType menuType, Map<Integer, String> slotActions, MenuConfig menuConfig) {
        this.menuType = menuType;
        this.slotActions = slotActions != null ? slotActions : Collections.emptyMap();
        this.menuConfig = menuConfig;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    @Override
    public @NotNull Inventory getInventory() {
        return inventory;
    }

    public MenuType getMenuType() {
        return menuType;
    }

    public Map<Integer, String> getSlotActions() {
        return slotActions;
    }

    public MenuConfig getMenuConfig() {
        return menuConfig;
    }
}
