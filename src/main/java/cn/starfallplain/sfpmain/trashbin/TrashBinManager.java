package cn.starfallplain.sfpmain.trashbin;

import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.module.TrashBinConfig;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 垃圾桶管理器：扫地时收集的掉落物不直接删除，而是存入垃圾桶。
 * 玩家可通过 /trashbin 打开垃圾桶界面取回物品，防止重要物品被误清。
 * <p>
 * 容量与保存策略来自 trashbin.yml。
 * 存储：全局共享一个物品列表（trashbin.yml 的 global 预留按玩家隔离）。
 * 持久化：trashbin-data.yml（ItemStack 可直接序列化）
 */
public class TrashBinManager {

    private final JavaPlugin plugin;
    private final TrashBinConfig config;
    private File dataFile;
    private FileConfiguration dataConfig;
    private final List<ItemStack> items = new ArrayList<>();

    public TrashBinManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = configManager.trashBin();
        loadData();
    }

    public TrashBinConfig getConfig() {
        return config;
    }

    /** 每页物品格数 */
    public int getPageSize() {
        return Math.max(1, Math.min(45, config.getPageSize()));
    }

    @SuppressWarnings("unchecked")
    private void loadData() {
        if (!plugin.getDataFolder().exists()) {
            plugin.getDataFolder().mkdirs();
        }
        dataFile = new File(plugin.getDataFolder(), "trashbin-data.yml");
        if (!dataFile.exists()) {
            try {
                dataFile.createNewFile();
            } catch (IOException e) {
                plugin.getLogger().warning("无法创建垃圾桶数据文件: " + e.getMessage());
            }
        }
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        List<?> loaded = dataConfig.getList("items");
        if (loaded != null) {
            for (Object o : loaded) {
                if (o instanceof ItemStack) {
                    items.add((ItemStack) o);
                }
            }
        }
    }

    /**
     * 保存到磁盘（同步）
     */
    public synchronized void save() {
        dataConfig.set("items", items);
        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存垃圾桶数据: " + e.getMessage());
        }
    }

    /** 超出上限时丢弃最旧的物品 */
    private void trim() {
        int max = config.getMaxItems();
        if (max > 0) {
            while (items.size() > max) {
                items.remove(0);
            }
        }
    }

    /**
     * 添加一个掉落物到垃圾桶
     */
    public synchronized void addItem(ItemStack item) {
        if (item == null || item.getType().isAir()) return;
        items.add(item.clone());
        trim();
    }

    /**
     * 清空垃圾桶（仅保留至下次扫地前；扫地时先清空再收入新掉落物）
     */
    public synchronized void clear() {
        items.clear();
    }

    /**
     * 批量添加（用于扫地）
     */
    public synchronized void addAll(List<ItemStack> newItems) {
        for (ItemStack item : newItems) {
            if (item != null && !item.getType().isAir()) {
                items.add(item.clone());
            }
        }
        trim();
    }

    /**
     * 取出指定全局索引的物品（玩家点击取回）
     *
     * @return 取出的物品；索引越界返回 null
     */
    public synchronized ItemStack takeItem(int globalIndex) {
        if (globalIndex < 0 || globalIndex >= items.size()) return null;
        return items.remove(globalIndex);
    }

    /**
     * 获取指定页的物品快照（用于展示，不可修改内部状态）
     *
     * @param page 0-based 页码
     * @return 该页的物品列表（pageSize 个元素，空位为 null）
     */
    public synchronized List<ItemStack> getPage(int page) {
        List<ItemStack> pageItems = new ArrayList<>();
        // 页码兜底夹紧：越界（含负数）会让下面的下标计算出负索引
        if (page < 0) page = 0;
        int start = page * getPageSize();
        for (int i = 0; i < getPageSize(); i++) {
            int idx = start + i;
            pageItems.add(idx < items.size() ? items.get(idx) : null);
        }
        return pageItems;
    }

    /**
     * 总页数（至少 1 页）
     */
    public synchronized int getPageCount() {
        return Math.max(1, (items.size() + getPageSize() - 1) / getPageSize());
    }

    /**
     * 当前物品数量
     */
    public synchronized int size() {
        return items.size();
    }

    /**
     * 将页面内槽位转换为全局索引
     */
    public int toGlobalIndex(int page, int slotInPage) {
        return page * getPageSize() + slotInPage;
    }
}
