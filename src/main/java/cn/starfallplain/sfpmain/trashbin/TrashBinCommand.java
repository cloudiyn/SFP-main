package cn.starfallplain.sfpmain.trashbin;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.util.SoundUtil;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * /trashbin 命令：打开扫地垃圾桶界面。
 * 玩家可在界面中取回被清扫的掉落物，防止重要物品被误清。
 * 外观文案来自 messages.yml 的 trashbin.*。
 */
public class TrashBinCommand implements BasicCommand {

    private final SfpMain plugin;

    public TrashBinCommand(SfpMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(@NotNull CommandSourceStack source, @NotNull String[] args) {
        CommandSender sender = source.getSender();
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.getMessage("common.player-only", "<red>该命令只能由玩家执行。</red>"));
            return;
        }
        if (!player.hasPermission("sfpmenu.player")) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return;
        }
        TrashBinManager manager = plugin.getTrashBinManager();
        if (manager == null) {
            player.sendMessage(plugin.getMessage("trashbin.disabled", "<red>垃圾桶系统未启用。</red>"));
            return;
        }
        openPage(plugin, player, manager, 0);
    }

    /**
     * 打开垃圾桶指定页（供命令和翻页按钮共用）
     */
    public static void openPage(SfpMain plugin, Player player, TrashBinManager manager, int page) {
        int pageCount = manager.getPageCount();
        if (page < 0) page = 0;
        if (page >= pageCount) page = pageCount - 1;

        int pageSize = manager.getPageSize();
        TrashBinHolder holder = new TrashBinHolder(page, pageSize);
        Map<String, String> ph = new HashMap<>();
        ph.put("page", String.valueOf(page + 1));
        ph.put("pages", String.valueOf(pageCount));
        ph.put("total", String.valueOf(manager.size()));

        String titleRaw = plugin.getRawMessage("trashbin.title",
                "<!i><dark_gray>垃圾桶</dark_gray> <gray>- 第 {page}/{pages} 页</gray>");
        Component title = Messages.deserialize(Messages.apply(titleRaw, ph));

        Inventory inv = org.bukkit.Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        // 物品格
        List<ItemStack> pageItems = manager.getPage(page);
        for (int i = 0; i < pageSize && i < 45; i++) {
            ItemStack it = pageItems.get(i);
            if (it != null) {
                inv.setItem(i, it.clone());
            }
        }

        // 导航行（45~53）：上一页 / 灰板 / 返回 / 页码 / 灰板 / 下一页
        ItemStack grayPane = createIcon(Material.GRAY_STAINED_GLASS_PANE, "<!i><dark_gray> </dark_gray>");
        for (int slot : new int[]{46, 47, 50, 51, 52}) {
            inv.setItem(slot, grayPane);
        }

        if (page > 0) {
            inv.setItem(TrashBinHolder.SLOT_PREV, createIcon(Material.SPECTRAL_ARROW,
                    plugin.getRawMessage("trashbin.prev", "<!i><yellow>上一页</yellow>")));
        } else {
            inv.setItem(TrashBinHolder.SLOT_PREV, createIcon(Material.GRAY_STAINED_GLASS_PANE,
                    plugin.getRawMessage("trashbin.first", "<!i><dark_gray>首页</dark_gray>")));
        }

        // 返回主菜单（点击由 TrashBinListener 处理）
        inv.setItem(TrashBinHolder.SLOT_BACK, createIcon(Material.OAK_DOOR,
                plugin.getRawMessage("common.back-to-menu", "<!i><yellow>返回主菜单</yellow>")));

        inv.setItem(TrashBinHolder.SLOT_INFO, createInfoItem(plugin, manager.size(), page + 1, pageCount, ph));

        if (page < pageCount - 1) {
            inv.setItem(TrashBinHolder.SLOT_NEXT, createIcon(Material.SPECTRAL_ARROW,
                    plugin.getRawMessage("trashbin.next", "<!i><yellow>下一页</yellow>")));
        } else {
            inv.setItem(TrashBinHolder.SLOT_NEXT, createIcon(Material.GRAY_STAINED_GLASS_PANE,
                    plugin.getRawMessage("trashbin.last", "<!i><dark_gray>末页</dark_gray>")));
        }

        player.openInventory(inv);
        SoundUtil.play(player, player.getLocation(), manager.getConfig().getOpenSound(), 0.6f, 1.0f);
    }

    private static ItemStack createIcon(Material material, String rawName) {
        ItemStack item = ItemStack.of(material, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.deserialize(rawName));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
            item.setItemMeta(meta);
        }
        return item;
    }

    private static ItemStack createInfoItem(SfpMain plugin, int total, int page, int pageCount,
                                            Map<String, String> ph) {
        ItemStack item = ItemStack.of(Material.PAPER, 1);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Messages.deserialize(plugin.getRawMessage("trashbin.info-name",
                    "<!i><aqua>垃圾桶信息</aqua>")));
            List<String> rawLore = plugin.getRawMessageList("trashbin.info-lore",
                    List.of("<!i><gray>当前页： <white>{page}/{pages}</white></gray>",
                            "<!i><gray>物品总数： <white>{total}</white></gray>",
                            "<!i><gray>点击物品即可取回</gray>"));
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
