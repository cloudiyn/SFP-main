package cn.starfallplain.sfpmain.menu;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.MenuConfig;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.gui.HomeListGui;
import cn.starfallplain.sfpmain.teleport.gui.TpaTargetGui;
import cn.starfallplain.sfpmain.teleport.gui.WarpListGui;
import cn.starfallplain.sfpmain.trashbin.TrashBinCommand;
import cn.starfallplain.sfpmain.trashbin.TrashBinManager;
import cn.starfallplain.sfpmain.ui.DialogMenu;
import cn.starfallplain.sfpmain.ui.DialogTeleportList;
import cn.starfallplain.sfpmain.ui.DialogTpaTarget;
import cn.starfallplain.sfpmain.ui.UiMode;
import cn.starfallplain.sfpmain.ui.UiPreferenceStore;
import cn.starfallplain.sfpmain.util.SoundUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 菜单界面构建器（完全由 menu.yml 驱动）。
 * <p>
 * 标题、行数、边框、按钮的槽位/材质/名称/动作全部读取配置；
 * 按钮的可见性同时受对应功能模块开关影响，功能关闭时入口自动隐藏。
 */
public class MenuManager {

    /** 标题从 menu.yml 读取，回退到 config.yml 的旧字段 */
    private static String resolveTitle(ConfigManager cm) {
        String title = cm.menu().getTitle();
        if (title == null || title.isBlank()) {
            title = cm.getPlugin().getConfig().getString("menu-title",
                    "<rainbow><b><i>星落平原 菜单</i></b></rainbow>");
        }
        return title;
    }

    // ==================== 主菜单 ====================

    /**
     * 打开主菜单，统一做「开关 + 打开权限」校验。
     * <p>
     * 供 /menu 命令与各子界面（垃圾桶 / 家列表 / 传送点列表）的「返回主菜单」按钮复用，
     * 保证所有入口行为一致。
     *
     * @return 是否成功打开了菜单（false 表示已向玩家发送了失败提示）
     */
    public static boolean openMainMenu(SfpMain plugin, Player player) {
        if (!canOpen(plugin, player)) return false;
        player.openInventory(createMainMenu(plugin, player));
        return true;
    }

    /** 主菜单的「开关 + 打开权限」校验；箱子 UI 与 dialogUI 共用，保证所有入口行为一致 */
    public static boolean canOpen(SfpMain plugin, Player player) {
        MenuConfig menuConfig = plugin.getConfigManager().menu();
        String permission = menuConfig.getOpenPermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            // 提示文案优先用 menu.yml 的 permission-message；留空则回退 messages.yml
            String message = menuConfig.getPermissionMessage();
            if (message == null || message.isBlank()) {
                player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            } else {
                player.sendMessage(Messages.deserialize(message));
            }
            return false;
        }
        if (!menuConfig.isEnabled()) {
            player.sendMessage(plugin.getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            return false;
        }
        return true;
    }

    /** 按玩家偏好打开主菜单（dialogUI 或箱子），供 /menu 与各子界面的「返回」共用 */
    public static void openMenuByPreference(SfpMain plugin, Player player) {
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        UiMode mode = store != null ? store.get(player.getUniqueId()) : UiMode.DIALOG;
        if (mode == UiMode.DIALOG) {
            DialogMenu.open(plugin, player);
        } else {
            openMainMenu(plugin, player);
        }
    }

    /**
     * 解析 menu.yml 得到「槽位 → 动作」映射。
     * <p>
     * 与 {@link #createMainMenu} 用的是同一套判定（模块开关、按钮开关、槽位范围），
     * 因此 /sfp test 查出来的绑定结果就是玩家实际点击的结果，不会出现「自检说绑上了、实际没绑」。
     */
    public static Map<Integer, String> resolveSlotActions(MenuConfig menuConfig, ConfigManager cm) {
        Map<Integer, String> slotActions = new LinkedHashMap<>();
        int size = menuConfig.getSize();

        for (MenuConfig.Button button : menuConfig.getButtons().values()) {
            if (!isFeatureEnabled(button.getId(), menuConfig, cm)) continue;
            if (!button.isVisible()) continue;

            int slot = button.getSlot();
            if (slot < 0 || slot >= size) continue;

            String action = resolveAction(button, menuConfig);
            if (action != null) {
                slotActions.put(slot, action);
            }
        }
        return slotActions;
    }

    /** 单个按钮对应的动作串；返回 null 表示该按钮没有任何绑定（点了没反应） */
    public static String resolveAction(MenuConfig.Button button, MenuConfig menuConfig) {
        if (button.getId().equals(menuConfig.getTrashBinButtonId())) return MenuHolder.ACTION_TRASHBIN;
        if (button.getId().equals(menuConfig.getHomeButtonId())) return MenuHolder.ACTION_HOME;
        if (button.getId().equals(menuConfig.getWarpButtonId())) return MenuHolder.ACTION_WARP;
        if (button.getId().equals(menuConfig.getBackButtonId())) return MenuHolder.ACTION_BACK;
        if (button.getId().equals(menuConfig.getTpaButtonId())) return MenuHolder.ACTION_TPA;
        if (button.getId().equals(menuConfig.getRtpButtonId())) return MenuHolder.ACTION_RTP;
        if (button.getId().equals(menuConfig.getElytraButtonId())) return MenuHolder.ACTION_ELYTRA;
        if (button.getId().equals(menuConfig.getUiToggleButtonId())) return MenuHolder.ACTION_TOGGLE_UI;
        if (!button.getCommand().isBlank()) return MenuHolder.CMD_PREFIX + button.getCommand();
        return null;
    }

    /**
     * 返回所有「可见且槽位合法」的按钮（按 menu.yml 顺序），供 dialogUI 渲染。
     * <p>
     * <b>包含没绑动作的说明性按钮</b>：箱子 UI 会把它显示出来，
     * 所以 dialogUI 也必须显示，否则同一份 menu.yml 在两种界面下按钮集合不一致。
     * 这类按钮点击不做任何事，由 {@link DialogMenu} 挂一个空回调实现。
     */
    public static List<MenuConfig.Button> activeButtons(MenuConfig menuConfig, ConfigManager cm) {
        List<MenuConfig.Button> result = new ArrayList<>();
        int size = menuConfig.getSize();
        for (MenuConfig.Button button : menuConfig.getButtons().values()) {
            if (!isFeatureEnabled(button.getId(), menuConfig, cm)) continue;
            if (!button.isVisible()) continue;
            if (button.getSlot() < 0 || button.getSlot() >= size) continue;
            result.add(button);
        }
        return result;
    }

    /**
     * 执行一个按钮动作。箱子 UI 与 dialogUI **共用同一套逻辑**，保证点击结果一致。
     * <p>
     * 调用方（箱子 UI 的点击处理 / dialogUI 的回调）负责在需要时先关闭自己的界面，
     * 这里只负责「把动作做出来」。
     */
    public static void runAction(SfpMain plugin, Player player, String action) {
        if (action == null) return;
        if (action.startsWith(MenuHolder.CMD_PREFIX)) {
            String command = action.substring(MenuHolder.CMD_PREFIX.length());
            Bukkit.getScheduler().runTask(plugin, () -> player.performCommand(command));
            return;
        }
        switch (action) {
            case MenuHolder.ACTION_TRASHBIN -> openTrashBin(plugin, player);
            case MenuHolder.ACTION_HOME -> openHomeList(plugin, player);
            case MenuHolder.ACTION_WARP -> openWarpList(plugin, player);
            case MenuHolder.ACTION_BACK ->
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("back"));
            case MenuHolder.ACTION_TPA -> openTpaTarget(plugin, player);
            case MenuHolder.ACTION_RTP ->
                    Bukkit.getScheduler().runTask(plugin, () -> player.performCommand("rtp"));
            case MenuHolder.ACTION_ELYTRA -> exchangeElytra(plugin, player);
            case MenuHolder.ACTION_TOGGLE_UI -> toggleUi(plugin, player);
            default -> { /* 未知动作：忽略 */ }
        }
    }

    private static void openTrashBin(SfpMain plugin, Player player) {
        TrashBinManager manager = plugin.getTrashBinManager();
        if (manager == null) {
            player.sendMessage(plugin.getConfigManager().messages().component("trashbin.disabled",
                    "<red>垃圾桶系统未启用。</red>"));
            return;
        }
        Bukkit.getScheduler().runTask(plugin, () ->
                TrashBinCommand.openPage(plugin, player, manager, 0));
    }

    /**
     * 打开家列表（按玩家偏好分流出 dialogUI 或箱子界面）。
     * <p>
     * 菜单按钮（{@code acthome}）与 {@code /home} 无参命令**共用这一个入口**，
     * 保证两个入口在两种界面下表现完全一致。
     */
    public static void openHomeList(SfpMain plugin, Player player) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            player.sendMessage(plugin.getConfigManager().messages().component("common.feature-disabled",
                    "<red>该功能当前未启用。</red>"));
            return;
        }
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        UiMode mode = store != null ? store.get(player.getUniqueId()) : UiMode.DIALOG;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode == UiMode.DIALOG) {
                DialogTeleportList.open(plugin, player, true, 0);
            } else {
                HomeListGui.open(plugin, player, manager, 0);
            }
        });
    }

    /**
     * 打开传送点列表（按玩家偏好分流出 dialogUI 或箱子界面）。
     * 与 {@link #openHomeList} 同理，供菜单按钮与 {@code /warp} 无参命令共用。
     */
    public static void openWarpList(SfpMain plugin, Player player) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            player.sendMessage(plugin.getConfigManager().messages().component("common.feature-disabled",
                    "<red>该功能当前未启用。</red>"));
            return;
        }
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        UiMode mode = store != null ? store.get(player.getUniqueId()) : UiMode.DIALOG;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode == UiMode.DIALOG) {
                DialogTeleportList.open(plugin, player, false, 0);
            } else {
                WarpListGui.open(plugin, player, manager, 0);
            }
        });
    }

    /**
     * 打开「选择传送目标」界面（按玩家偏好分流）。
     * 供菜单按钮（{@code acttpa}）调用；命令入口是 {@code /tpa <玩家名>}，不需要列表。
     */
    public static void openTpaTarget(SfpMain plugin, Player player) {
        if (plugin.getTpaManager() == null) {
            player.sendMessage(plugin.getConfigManager().messages().component("common.feature-disabled",
                    "<red>该功能当前未启用。</red>"));
            return;
        }
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        UiMode mode = store != null ? store.get(player.getUniqueId()) : UiMode.DIALOG;
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (mode == UiMode.DIALOG) {
                DialogTpaTarget.open(plugin, player, 0);
            } else {
                TpaTargetGui.open(plugin, player, 0);
            }
        });
    }

    /** 切换界面样式，并按新偏好重开菜单 */
    private static void toggleUi(SfpMain plugin, Player player) {
        UiPreferenceStore store = plugin.getUiPreferenceStore();
        if (store == null) return;
        UiMode next = store.get(player.getUniqueId()).toggle();
        store.set(player.getUniqueId(), next);
        player.sendMessage(plugin.getConfigManager().messages().component(
                next == UiMode.DIALOG ? "menuui.toggled-dialog" : "menuui.toggled-box",
                next == UiMode.DIALOG
                        ? "<green>界面已切换为 dialogUI（弹窗界面）。</green>"
                        : "<green>界面已切换为箱子界面。</green>"));
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) return;
            if (next == UiMode.DIALOG) {
                DialogMenu.open(plugin, player);
            } else {
                openMainMenu(plugin, player);
            }
        });
    }

    /**
     * 消耗材料兑换一个鞘翅（2.10.2 起支持「钻石 <b>或</b> 下界残骸」二选一）。
     * <p>
     * 无论箱子 UI 还是 dialogUI，点击「鞘翅兑换」按钮最终都走到这里，行为一致。
     * 流程：先判断材料够不够 → 够才扣除 → 给一个鞘翅；背包放不下时掉在脚下，
     * 避免物品凭空消失。
     * <p>
     * 扣除顺序：**优先扣钻石**，钻石不够再扣残骸。因此玩家两种材料都有、
     * 但只有钻石够时，扣的只会是钻石（不会两种各扣一点）。
     * <p>
     * 统计与扣除都只针对**存储槽位**（{@code getStorageContents()}），副手与盔甲槽不计入 ——
     * 因为 Bukkit 的 {@code removeItem} 本身就只作用于存储槽位，两边口径必须一致，
     * 否则会出现「数到了副手的钻石、却扣不掉」的错位。
     */
    private static void exchangeElytra(SfpMain plugin, Player player) {
        Messages messages = plugin.getConfigManager().messages();
        MenuConfig menuConfig = plugin.getConfigManager().menu();
        int diamondCost = menuConfig.getElytraDiamondCost();
        int debrisCost = menuConfig.getElytraDebrisCost();

        if (!menuConfig.isElytraExchangeUsable()) {
            plugin.getLogger().warning("menu.yml 的 elytra-exchange 把钻石与残骸都配成了 0，鞘翅兑换已停用。");
            messages.send(player, "menu.elytra-misconfigured",
                    "<red>鞘翅兑换当前不可用，请联系管理员。</red>", Map.of());
            return;
        }

        int diamondHave = countMaterial(player, Material.DIAMOND);
        int debrisHave = debrisCost > 0 ? countMaterial(player, Material.ANCIENT_DEBRIS) : 0;

        // 先判定，不够就直接返回，不做任何扣除
        boolean payByDiamond = diamondCost > 0 && diamondHave >= diamondCost;
        boolean payByDebris = !payByDiamond && debrisCost > 0 && debrisHave >= debrisCost;
        if (!payByDiamond && !payByDebris) {
            messages.send(player, "menu.elytra-not-enough",
                    "<red>材料不足，兑换一个鞘翅需要 {cost_need}。</red>",
                    requirement(diamondCost, debrisCost, diamondHave, debrisHave,
                            Material.DIAMOND, 0));
            SoundUtil.play(player, player.getLocation(), "entity.villager.no", 1.0f, 1.0f);
            return;
        }

        // 扣除材料（removeItem 会按数量分摊到多个堆叠中）
        Material payMaterial = payByDiamond ? Material.DIAMOND : Material.ANCIENT_DEBRIS;
        int payAmount = payByDiamond ? diamondCost : debrisCost;
        player.getInventory().removeItem(new ItemStack(payMaterial, payAmount));

        // 给出鞘翅；背包满则掉在脚下
        ItemStack elytra = ItemStack.of(Material.ELYTRA, 1);
        Map<Integer, ItemStack> leftover = player.getInventory().addItem(elytra);
        for (ItemStack rest : leftover.values()) {
            player.getWorld().dropItemNaturally(player.getLocation(), rest);
        }

        messages.send(player, "menu.elytra-success",
                "<green>兑换成功！已消耗 {cost_text}，获得 1 个鞘翅。</green>",
                requirement(diamondCost, debrisCost, diamondHave, debrisHave, payMaterial, payAmount));
        SoundUtil.play(player, player.getLocation(), "entity.player.levelup", 1.0f, 1.2f);
    }

    /**
     * 拼出兑换文案用的占位符表。
     * <p>
     * 占位符分三类：
     * <ul>
     *   <li>{@code {cost}} —— 本次**实际扣除的数量**（纯数字，兼容旧文案「已消耗 {cost} 个钻石」）</li>
     *   <li>{@code {cost_text}} —— 实际扣除的材料描述（如「64 个钻石」）</li>
     *   <li>{@code {cost_need}} —— 本次兑换的**要求**（两种材料都接受时为「A 或 B」）</li>
     * </ul>
     * 另有 {diamond}/{debris}（各自所需）、{diamond_have}/{debris_have}（各自持有），
     * 让「不足」提示能把差多少说清楚。
     * <p>
     * 保留旧占位符 {@code {have}}（= 钻石持有量）作为**向后兼容别名**：
     * 2.10.0/2.10.1 的线上 messages.yml 里 {@code menu.elytra-not-enough} 用的是
     * {@code {have}}，而 {@code mergeDefaults()} 只补缺失键、不覆盖已存在的键 ——
     * 升到 2.10.2 后那条旧文案不会被刷新。若不提供该别名，玩家会看到字面量 {@code {have}}。
     */
    private static Map<String, String> requirement(int diamondCost, int debrisCost,
                                                  int diamondHave, int debrisHave,
                                                  Material payMaterial, int payAmount) {
        Map<String, String> ph = new LinkedHashMap<>();
        // {cost} = 本次实际扣除的**数量**（纯数字）。
        // 保持为纯数字是有意的：2.10.0/2.10.1 的旧文案是「已消耗 {cost} 个钻石」，
        // 若这里塞「64 个钻石 或 8 个下界残骸」这种短语，会拼成「…个钻石 或 …个残骸 个钻石」。
        ph.put("cost", String.valueOf(payAmount));
        // {cost_text} = 带材料名的完整描述，供新文案使用
        ph.put("cost_text", payMaterial == Material.DIAMOND
                ? payAmount + " 个钻石"
                : payAmount + " 个下界残骸");
        // {cost_need} = 本次兑换的**要求**（两种材料都接受时说明「A 或 B」）
        if (diamondCost > 0 && debrisCost > 0) {
            ph.put("cost_need", diamondCost + " 个钻石 或 " + debrisCost + " 个下界残骸");
        } else if (diamondCost > 0) {
            ph.put("cost_need", diamondCost + " 个钻石");
        } else {
            ph.put("cost_need", debrisCost + " 个下界残骸");
        }
        ph.put("diamond", String.valueOf(diamondCost));
        ph.put("debris", String.valueOf(debrisCost));
        ph.put("diamond_have", String.valueOf(diamondHave));
        ph.put("debris_have", String.valueOf(debrisHave));
        // 兼容 2.10.0/2.10.1 的旧文案：老 messages.yml 里的 {have} 指「钻石持有量」
        ph.put("have", String.valueOf(diamondHave));
        return ph;
    }

    /** 统计玩家**存储槽位**里某种材质的总数量（与 removeItem 的口径一致，不含副手/盔甲） */
    private static int countMaterial(Player player, Material material) {
        int total = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    public static Inventory createMainMenu(SfpMain plugin, Player player) {
        ConfigManager cm = plugin.getConfigManager();
        MenuConfig menuConfig = cm.menu();
        Messages messages = cm.messages();

        int size = menuConfig.getSize();
        Map<Integer, String> slotActions = resolveSlotActions(menuConfig, cm);
        MenuHolder holder = new MenuHolder(MenuHolder.MenuType.MAIN, slotActions, menuConfig);

        Component title = Messages.deserialize(resolveTitle(cm));
        Inventory inv = Bukkit.createInventory(holder, size, title);
        holder.setInventory(inv);
        applyBorder(inv, menuConfig);

        // 顶栏玩家头颅（配置可关）
        if (menuConfig.isShowPlayerHead() && menuConfig.getPlayerHeadSlot() < size) {
            inv.setItem(menuConfig.getPlayerHeadSlot(), createPlayerHead(player,
                    messages.raw("menu.head-name", "<!i><yellow>{player}</yellow>")
                            .replace("{player}", player.getName())));
        }

        for (MenuConfig.Button button : menuConfig.getButtons().values()) {
            if (!isFeatureEnabled(button.getId(), menuConfig, cm)) continue;
            if (!button.isVisible()) continue;

            int slot = button.getSlot();
            if (slot < 0 || slot >= size) {
                plugin.getLogger().warning("menu.yml 中按钮 '" + button.getId()
                        + "' 的槽位 " + slot + " 超出菜单范围（0~" + (size - 1) + "），已忽略。");
                continue;
            }
            inv.setItem(slot, createButton(button));
        }

        return inv;
    }

    /** 按钮绑定的功能模块是否启用 */
    private static boolean isFeatureEnabled(String buttonId, MenuConfig menuConfig, ConfigManager cm) {
        if (buttonId.equals(menuConfig.getTrashBinButtonId())) return cm.trashBin().isEnabled();
        if (buttonId.equals(menuConfig.getHomeButtonId())) return cm.teleport().isEnabled() && cm.teleport().isHomeEnabled();
        if (buttonId.equals(menuConfig.getWarpButtonId())) return cm.teleport().isEnabled() && cm.teleport().isWarpEnabled();
        if (buttonId.equals(menuConfig.getBackButtonId())) return cm.teleport().isEnabled() && cm.teleport().isBackEnabled();
        if (buttonId.equals(menuConfig.getTpaButtonId())) return cm.teleport().isEnabled() && cm.teleport().isTpaEnabled();
        if (buttonId.equals(menuConfig.getRtpButtonId())) return cm.teleport().isEnabled() && cm.teleport().isRtpEnabled();
        return true;
    }

    private static ItemStack createButton(MenuConfig.Button button) {
        ItemStack item = ItemStack.of(button.getMaterial(), 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.deserialize("<!i>" + button.getName()));
            if (button.getLore() != null && !button.getLore().isEmpty()) {
                List<Component> lore = new ArrayList<>();
                for (String line : button.getLore()) {
                    lore.add(Messages.deserialize(line));
                }
                meta.lore(lore);
            }
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    // ==================== 工具方法 ====================

    private static void applyBorder(Inventory inv, MenuConfig menuConfig) {
        if (!menuConfig.isFillBorder()) return;
        int size = inv.getSize();
        int rows = size / 9;
        List<Material> materials = menuConfig.getBorderMaterials();
        int index = 0;
        for (int slot = 0; slot < size; slot++) {
            int row = slot / 9;
            int col = slot % 9;
            boolean border = row == 0 || row == rows - 1 || col == 0 || col == 8;
            if (border && inv.getItem(slot) == null) {
                Material material = materials.get(index % materials.size());
                inv.setItem(slot, ItemStack.of(material, 1));
                index++;
            }
        }
    }

    private static ItemStack createPlayerHead(Player player, String rawName) {
        ItemStack head = ItemStack.of(Material.PLAYER_HEAD, 1);
        if (head.getItemMeta() instanceof SkullMeta meta) {
            meta.setOwningPlayer(player);
            meta.displayName(Messages.deserialize(rawName));
            head.setItemMeta(meta);
        }
        return head;
    }
}
