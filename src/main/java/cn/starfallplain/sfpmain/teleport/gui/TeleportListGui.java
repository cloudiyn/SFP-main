package cn.starfallplain.sfpmain.teleport.gui;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import cn.starfallplain.sfpmain.util.SoundUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 传送列表 GUI 构建器（家列表 / 传送点列表共用）。
 * <p>
 * 布局沿用垃圾桶的分页样式：内容区 0~44，导航行 45~53
 * （上一页 / 灰板 / 页码信息 / 灰板 / 下一页）。
 * <ul>
 *   <li>左键点击条目：传送到该位置</li>
 *   <li>右键点击条目：删除（家直接删；传送点需权限）</li>
 * </ul>
 */
public final class TeleportListGui {

    private TeleportListGui() {
    }

    /**
     * 打开指定列表页。
     *
     * @param type 列表类型（HOME / WARP）
     * @param page 0-based 页码
     */
    public static void open(SfpMain plugin, Player player, TeleportManager manager,
                            TeleportListHolder.ListType type, int page) {
        List<String> allNames = type == TeleportListHolder.ListType.HOME
                ? manager.getHomeStore().listNames(player.getUniqueId())
                : manager.getWarpStore().listNames();

        int pageSize = 45;
        int pageCount = Math.max(1, (allNames.size() + pageSize - 1) / pageSize);
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        // 收集当前页名称
        List<String> pageNames = new ArrayList<>();
        int start = page * pageSize;
        for (int i = 0; i < pageSize; i++) {
            int idx = start + i;
            pageNames.add(idx < allNames.size() ? allNames.get(idx) : null);
        }

        TeleportListHolder holder = new TeleportListHolder(type, page, pageSize, pageNames);

        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(page + 1));
        ph.put("pages", String.valueOf(pageCount));
        ph.put("total", String.valueOf(allNames.size()));

        String titleKey = type == TeleportListHolder.ListType.HOME ? "home.title" : "warp.title";
        String titleDef = type == TeleportListHolder.ListType.HOME
                ? "<!i><aqua>我的家</aqua> <gray>- 第 {page}/{pages} 页</gray>"
                : "<!i><light_purple>传送点</light_purple> <gray>- 第 {page}/{pages} 页</gray>";
        Component title = Messages.deserialize(Messages.apply(plugin.getRawMessage(titleKey, titleDef), ph));

        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        // 内容格
        Material icon = type == TeleportListHolder.ListType.HOME ? Material.RED_BED : Material.ENDER_PEARL;
        String warpDeletePerm = manager.getConfig().getWarpDeletePermission();
        boolean canDeleteWarp = warpDeletePerm == null || warpDeletePerm.isBlank()
                || player.hasPermission(warpDeletePerm);
        for (int i = 0; i < pageSize; i++) {
            String name = pageNames.get(i);
            if (name == null) continue;
            StoredLocation loc = type == TeleportListHolder.ListType.HOME
                    ? manager.getHomeStore().get(player.getUniqueId(), name)
                    : manager.getWarpStore().get(name);
            inv.setItem(i, createEntry(player, type, name, loc, icon, canDeleteWarp));
        }

        // 导航行：上一页 / 灰板 / 返回 / 页码 / 灰板 / 下一页
        ItemStack grayPane = createIcon(Material.GRAY_STAINED_GLASS_PANE, "<!i><dark_gray> </dark_gray>");
        for (int slot : new int[]{46, 47, 50, 51, 52}) {
            inv.setItem(slot, grayPane);
        }

        if (page > 0) {
            inv.setItem(TeleportListHolder.SLOT_PREV, createIcon(Material.SPECTRAL_ARROW,
                    plugin.getRawMessage("teleport.prev", "<!i><yellow>上一页</yellow>")));
        } else {
            inv.setItem(TeleportListHolder.SLOT_PREV, createIcon(Material.GRAY_STAINED_GLASS_PANE,
                    plugin.getRawMessage("teleport.first", "<!i><dark_gray>首页</dark_gray>")));
        }

        // 返回主菜单（点击由 TeleportGuiListener 处理）
        inv.setItem(TeleportListHolder.SLOT_BACK, createIcon(Material.OAK_DOOR,
                plugin.getRawMessage("common.back-to-menu", "<!i><yellow>返回主菜单</yellow>")));

        inv.setItem(TeleportListHolder.SLOT_INFO, createInfo(plugin, type, allNames.size(), page + 1, pageCount, ph));

        if (page < pageCount - 1) {
            inv.setItem(TeleportListHolder.SLOT_NEXT, createIcon(Material.SPECTRAL_ARROW,
                    plugin.getRawMessage("teleport.next", "<!i><yellow>下一页</yellow>")));
        } else {
            inv.setItem(TeleportListHolder.SLOT_NEXT, createIcon(Material.GRAY_STAINED_GLASS_PANE,
                    plugin.getRawMessage("teleport.last", "<!i><dark_gray>末页</dark_gray>")));
        }

        player.openInventory(inv);
        SoundUtil.play(player, player.getLocation(),
                plugin.getConfigManager().teleport().getTeleportSound(), 0.4f, 1.4f);
    }

    private static ItemStack createEntry(Player player, TeleportListHolder.ListType type,
                                         String name, StoredLocation loc, Material icon,
                                         boolean canDeleteWarp) {
        ItemStack item = ItemStack.of(icon, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.deserialize("<!i><white>" + name + "</white>"));
            List<Component> lore = new ArrayList<>();
            lore.add(Messages.deserialize("<!i><gray>坐标： <white>"
                    + (loc != null ? loc.describe() : "?") + "</white></gray>"));
            lore.add(Messages.deserialize("<!i><green>左键</green><gray> 传送</gray>"));
            if (type == TeleportListHolder.ListType.HOME) {
                lore.add(Messages.deserialize("<!i><red>右键</red><gray> 删除</gray>"));
            } else if (canDeleteWarp) {
                lore.add(Messages.deserialize("<!i><red>右键</red><gray> 删除（管理员）</gray>"));
            } else {
                lore.add(Messages.deserialize("<!i><dark_gray>公共传送点，不可删除</dark_gray>"));
            }
            meta.lore(lore);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createIcon(Material material, String rawName) {
        return createIcon(material, Messages.deserialize(rawName));
    }

    private static ItemStack createIcon(Material material, Component name) {
        ItemStack item = ItemStack.of(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createInfo(SfpMain plugin, TeleportListHolder.ListType type,
                                        int total, int page, int pageCount, Map<String, String> ph) {
        ItemStack item = ItemStack.of(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String infoNameKey = type == TeleportListHolder.ListType.HOME ? "home.info-name" : "warp.info-name";
            String infoNameDef = type == TeleportListHolder.ListType.HOME
                    ? "<!i><aqua>家列表信息</aqua>" : "<!i><light_purple>传送点信息</light_purple>";
            meta.displayName(Messages.deserialize(plugin.getRawMessage(infoNameKey, infoNameDef)));

            String loreKey = type == TeleportListHolder.ListType.HOME ? "home.info-lore" : "warp.info-lore";
            List<String> rawLore = plugin.getRawMessageList(loreKey,
                    List.of("<!i><gray>当前页： <white>{page}/{pages}</white></gray>",
                            "<!i><gray>条目总数： <white>{total}</white></gray>",
                            "<!i><gray>左键传送</gray>"));
            List<Component> lore = new ArrayList<>(rawLore.size());
            for (String line : rawLore) {
                lore.add(Messages.deserialize(Messages.apply(line, ph)));
            }
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }
}
