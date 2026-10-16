package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.config.AbstractConfig;
import org.bukkit.Material;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 主菜单配置（menu.yml）：
 * 标题、行数、边框玻璃，以及每个按钮的槽位/材质/名称/描述/动作。
 * <p>
 * 按钮在 menu.yml 中以 {@code buttons.<id>} 定义，每个按钮字段：
 * <pre>
 *   enabled        是否显示
 *   slot           所在槽位（行数决定的上限内）
 *   material       Bukkit Material
 *   name           显示名（MiniMessage）
 *   lore           描述（MiniMessage 列表，可空）
 *   command        点击执行的命令（不含斜杠；留空表示由插件内部处理）
 * </pre>
 */
public final class MenuConfig extends AbstractConfig {

    /**
     * 单个按钮定义。
     */
    public static final class Button {
        private final String id;
        private final boolean enabled;
        private final int slot;
        private final Material material;
        private final String name;
        private final List<String> lore;
        private final String command;

        Button(String id, boolean enabled, int slot, Material material,
               String name, List<String> lore, String command) {
            this.id = id;
            this.enabled = enabled;
            this.slot = slot;
            this.material = material;
            this.name = name;
            this.lore = lore;
            this.command = command;
        }

        public String getId() { return id; }

        public boolean isEnabled() { return enabled; }

        public int getSlot() { return slot; }

        public Material getMaterial() { return material; }

        public String getName() { return name; }

        public List<String> getLore() { return lore; }

        public String getCommand() { return command; }

        /** 该按钮是否可见（所属模块开关由 MenuManager 另行判断） */
        public boolean isVisible() {
            return enabled;
        }
    }

    // 主菜单
    private boolean enabled;
    private String title;
    private int rows;
    private boolean fillBorder;
    private List<Material> borderMaterials;

    // 按钮
    // 注意：这里**不要**写成 `= new LinkedHashMap<>()`。
    // Java 是在 super(...) 返回之后才执行子类字段初始化器的，而 onLoaded() 恰恰是从
    // AbstractConfig 的构造器里调用的 —— 那一刻本字段还是 null，会直接 NPE；
    // 即便绕开 NPE，初始化器随后也会把 onLoaded() 填好的内容覆盖成空表。
    // 因此改为在 onLoaded() 内部新建。
    private Map<String, Button> buttons;

    // 垃圾桶按钮 id（用于绑定模块开关）
    private String trashBinButtonId;
    // 传送相关按钮 id（home / warp / back）
    private String homeButtonId;
    private String warpButtonId;
    private String backButtonId;
    // 玩家传送（tpa）按钮 id
    private String tpaButtonId;
    // 随机传送（rtp）按钮 id
    private String rtpButtonId;
    // 鞘翅兑换按钮 id
    private String elytraButtonId;
    // 鞘翅兑换消耗的钻石数量（0 = 不接受钻石）
    private int elytraDiamondCost;
    // 鞘翅兑换消耗的下界残骸数量（0 = 不接受残骸）
    private int elytraDebrisCost;
    // 界面样式切换按钮 id
    private String uiToggleButtonId;

    // 玩家头颅位置
    private boolean showPlayerHead;
    private int playerHeadSlot;

    // 菜单钟：把一个泥土放进合成格即可合成，手持右键打开菜单
    private boolean menuClockEnabled;
    private Material menuClockMaterial;
    private String menuClockName;
    private List<String> menuClockLore;
    private boolean menuClockGlint;
    private String menuClockTrigger;
    private boolean menuClockRecipeEnabled;
    private List<Material> menuClockIngredients;
    /** 玩家进服时提示「可以合成菜单钟」的消息（列表，逐行发送；空 = 不提示） */
    private List<String> menuClockJoinNotice;

    // 权限
    private String openPermission;
    private String permissionMessage;

    public MenuConfig(JavaPlugin plugin) {
        super(plugin, "menu.yml");
    }

    @Override
    protected void onLoaded() {
        enabled = getBoolean("enabled", true);
        title = getString("title", "<rainbow><b><i>星落平原 菜单</i></b></rainbow>");
        rows = Math.max(1, Math.min(6, getInt("rows", 3)));
        fillBorder = getBoolean("border.fill", true);
        borderMaterials = readMaterials("border.materials");
        if (borderMaterials.isEmpty()) {
            borderMaterials = List.of(Material.GRAY_STAINED_GLASS_PANE);
        }

        showPlayerHead = getBoolean("player-head.enabled", true);
        playerHeadSlot = getInt("player-head.slot", 4);

        // 菜单钟
        menuClockEnabled = getBoolean("menu-clock.enabled", true);
        menuClockMaterial = readMaterial("menu-clock.material", Material.CLOCK);
        menuClockName = getString("menu-clock.name", "<!i><rainbow>星落平原 菜单钟</rainbow>");
        menuClockLore = getStringList("menu-clock.lore");
        if (menuClockLore.isEmpty()) {
            menuClockLore = List.of("<!i><gray>右键打开星落平原菜单</gray>");
        }
        menuClockGlint = getBoolean("menu-clock.glint", true);
        menuClockTrigger = getString("menu-clock.trigger", "both").trim().toLowerCase();
        menuClockRecipeEnabled = getBoolean("menu-clock.recipe.enabled", true);
        menuClockIngredients = readMaterials("menu-clock.recipe.ingredients");
        if (menuClockIngredients.isEmpty()) {
            // 默认「一个泥土」：材质缺失或全部无效时兜底
            menuClockIngredients = List.of(Material.DIRT);
        }
        // 进服提示（列表；空的列表表示不提示）
        menuClockJoinNotice = getStringList("menu-clock.join-notice");
        if (menuClockJoinNotice.isEmpty()) {
            menuClockJoinNotice = List.of(
                    "<!i><gray>把</gray><white>泥土</white><gray>放进合成格，就能合成</gray>"
                            + "<rainbow>菜单钟</rainbow><gray>，手持右键即可打开菜单。</gray>");
        }

        trashBinButtonId = getString("bind.trashbin-button", "trashbin");
        homeButtonId = getString("bind.home-button", "home");
        warpButtonId = getString("bind.warp-button", "warp");
        backButtonId = getString("bind.back-button", "back");
        tpaButtonId = getString("bind.tpa-button", "tpa");
        rtpButtonId = getString("bind.rtp-button", "rtp");
        elytraButtonId = getString("bind.elytra-button", "elytra");
        // 兑换代价（2.10.2 起支持「钻石 或 下界残骸」二选一）：
        // 两个都填则优先扣钻石，钻石不够再试残骸；填 0 表示不接受该材料。
        // 兼容旧配置：老的单个 cost 键仍按「钻石数量」读取。
        int legacyCost = getInt("elytra-exchange.cost", 0);
        elytraDiamondCost = Math.max(0, getInt("elytra-exchange.diamond-cost", legacyCost > 0 ? legacyCost : 64));
        elytraDebrisCost = Math.max(0, getInt("elytra-exchange.debris-cost", 8));
        uiToggleButtonId = getString("bind.ui-toggle-button", "ui-toggle");

        openPermission = getString("permission", "sfpmenu.player");
        permissionMessage = getString("permission-message", "你没有权限使用此命令");

        // 每次加载都重建：既规避构造期的字段初始化顺序问题（见字段声明处注释），
        // 也保证 reload 时不残留上一次的按钮
        buttons = new LinkedHashMap<>();
        var section = raw().getConfigurationSection(normalize("buttons"));
        if (section != null) {
            for (String id : section.getKeys(false)) {
                String base = normalize("buttons") + "." + id;
                boolean btnEnabled = getBoolean(base + ".enabled", true);
                int slot = getInt(base + ".slot", -1);
                Material material = readMaterial(base + ".material", Material.STONE);
                String name = getString(base + ".name", id);
                List<String> lore = getStringList(base + ".lore");
                String command = getString(base + ".command", "");
                buttons.put(id, new Button(id, btnEnabled, slot, material, name, lore, command));
            }
        }
    }

    private Material readMaterial(String path, Material def) {
        String raw = getString(path, def.name());
        Material material = Material.matchMaterial(raw.trim());
        if (material == null) {
            plugin.getLogger().warning("menu.yml 中无效的材质 '" + raw + "'（" + path + "），已回退为 " + def.name());
            return def;
        }
        return material;
    }

    private List<Material> readMaterials(String path) {
        List<Material> list = new ArrayList<>();
        for (String raw : getStringList(path)) {
            Material material = Material.matchMaterial(raw.trim());
            if (material != null) {
                list.add(material);
            } else {
                plugin.getLogger().warning("menu.yml 中无效的材质 '" + raw + "'（" + path + "）已跳过");
            }
        }
        return list;
    }

    public boolean isEnabled() { return enabled; }

    public String getTitle() { return title; }

    public int getRows() { return rows; }

    public int getSize() { return rows * 9; }

    public boolean isFillBorder() { return fillBorder; }

    public List<Material> getBorderMaterials() { return borderMaterials; }

    public Map<String, Button> getButtons() { return buttons; }

    public Button getButton(String id) {
        return id == null ? null : buttons.get(id);
    }

    public String getTrashBinButtonId() { return trashBinButtonId; }

    public String getHomeButtonId() { return homeButtonId; }

    public String getWarpButtonId() { return warpButtonId; }

    public String getBackButtonId() { return backButtonId; }

    public String getTpaButtonId() { return tpaButtonId; }

    public String getRtpButtonId() { return rtpButtonId; }

    public String getElytraButtonId() { return elytraButtonId; }

    /** 兑换一个鞘翅需要的钻石数量（0 = 不接受钻石） */
    public int getElytraDiamondCost() { return elytraDiamondCost; }

    /** 兑换一个鞘翅需要的下界残骸数量（0 = 不接受残骸） */
    public int getElytraDebrisCost() { return elytraDebrisCost; }

    /** 两种材料都没配（都填 0）时按钮点了没反应，这里给个兜底：至少要能兑换一次 */
    public boolean isElytraExchangeUsable() {
        return elytraDiamondCost > 0 || elytraDebrisCost > 0;
    }

    public String getUiToggleButtonId() { return uiToggleButtonId; }

    public boolean isShowPlayerHead() { return showPlayerHead; }

    public int getPlayerHeadSlot() { return playerHeadSlot; }

    public boolean isMenuClockEnabled() { return menuClockEnabled; }

    public Material getMenuClockMaterial() { return menuClockMaterial; }

    public String getMenuClockName() { return menuClockName; }

    public List<String> getMenuClockLore() { return menuClockLore; }

    public boolean isMenuClockGlint() { return menuClockGlint; }

    /** 触发方式：both=看向方块与空气都触发；air=只在不看向方块时触发（不拦截方块交互） */
    public String getMenuClockTrigger() { return menuClockTrigger; }

    public boolean isMenuClockRecipeEnabled() { return menuClockRecipeEnabled; }

    public List<Material> getMenuClockIngredients() { return menuClockIngredients; }

    /** 进服提示（可多行）；空列表表示不提示 */
    public List<String> getMenuClockJoinNotice() { return menuClockJoinNotice; }

    public String getOpenPermission() { return openPermission; }

    public String getPermissionMessage() { return permissionMessage; }
}
