package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.bot.BotSettings;
import cn.starfallplain.sfpmain.config.AbstractConfig;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 假人配置（bot.yml）。
 * <p>
 * 假人是**真正的玩家实体**（NMS ServerPlayer），不是盔甲架之类的近似物：
 * 它会出现在在线玩家列表里，并天然保持周围区块加载。详见 bot 包的 NmsBotFactory。
 * <p>
 * 除创建 / 重建相关参数外，还包含**持续动作**的执行参数（攻击范围、执行间隔……）
 * 与新建假人的**默认设置**（{@code defaults} 段，可被每位玩家的个人配置覆盖）。
 */
public final class BotConfig extends AbstractConfig {

    private Set<Material> pickupWhitelist;
    private List<String> invalidPickupMaterials;
    private boolean pickupWhitelistRestricted;

    private boolean enabled;
    private boolean restoreOnStart;
    private String skinSource;
    private int maxNameLength;
    private int maxPerPlayer;

    /** 持续动作的执行间隔（tick） */
    private int actionIntervalTicks;
    /** 持续攻击时搜索实体的半径 */
    private double attackRange;
    /** 持续攻击是否把玩家也算作目标（默认 false，避免误伤） */
    private boolean attackPlayers;
    /** 超级回复的回血间隔（tick） */
    private int wolverineIntervalTicks;
    /** 自动补充快捷栏的检查间隔（tick） */
    private int replenishIntervalTicks;
    /** 自动钓鱼：抛竿后等待咬钩的最长时间（秒），超时收竿重抛 */
    private int autofishTimeoutSeconds;

    /**
     * 新建假人的默认设置（键为 BotSettings，值缺失时用 BotSettings.DEFAULTS）。
     * <p>
     * ⚠ 不要在字段初始化器里 {@code new}：{@code super()} 返回后初始化器才会执行，
     * 会把 {@link #onLoaded()} 填好的内容覆盖成空表（AbstractConfig 的初始化顺序坑）。
     */
    private Map<BotSettings, Boolean> defaults;

    public BotConfig(JavaPlugin plugin) {
        super(plugin, "bot.yml");
    }

    @Override
    protected void onLoaded() {
        enabled = getBoolean("enabled", true);
        restoreOnStart = getBoolean("restore-on-start", true);
        skinSource = getString("skin-source", "");
        maxNameLength = Math.max(1, Math.min(16, getInt("max-name-length", 16)));
        maxPerPlayer = Math.max(0, getInt("max-per-player", 3));

        actionIntervalTicks = Math.max(1, Math.min(200, getInt("action-interval-ticks", 4)));
        attackRange = Math.max(1.0, Math.min(32.0, getDouble("attack-range", 4.0)));
        attackPlayers = getBoolean("attack-players", false);
        wolverineIntervalTicks = Math.max(5, Math.min(1200, getInt("wolverine-interval-ticks", 20)));
        replenishIntervalTicks = Math.max(5, Math.min(1200, getInt("replenish-interval-ticks", 20)));
        autofishTimeoutSeconds = Math.max(3, Math.min(600, getInt("autofish-timeout-seconds", 30)));

        // 非空但全写错的白名单也保持限制，不能意外退回全部拾取。
        pickupWhitelist = EnumSet.noneOf(Material.class);
        invalidPickupMaterials = new ArrayList<>();
        List<String> configured = getStringList("pickup-whitelist");
        pickupWhitelistRestricted = !configured.isEmpty();
        for (String name : configured) {
            Material material = Material.matchMaterial(name);
            if (material == null || !material.isItem() || material.isAir()) {
                invalidPickupMaterials.add(name);
                plugin.getLogger().warning("假人拾取白名单包含无效物品：" + name);
            } else {
                pickupWhitelist.add(material);
            }
        }

        // 默认设置：逐个键读，缺键用 BotSettings.DEFAULTS
        defaults = new LinkedHashMap<>();
        ConfigurationSection section = raw().getConfigurationSection("defaults");
        for (BotSettings setting : BotSettings.values()) {
            boolean fallback = BotSettings.DEFAULTS.getOrDefault(setting, true);
            boolean value = section != null
                    ? section.getBoolean(setting.key(), fallback)
                    : fallback;
            defaults.put(setting, value);
        }
    }

    public boolean isPickupAllowed(Material material) {
        return !pickupWhitelistRestricted || pickupWhitelist.contains(material);
    }

    public List<String> getInvalidPickupMaterials() { return List.copyOf(invalidPickupMaterials); }

    public boolean isEnabled() { return enabled; }

    /** 启动时是否按 bots.yml 自动重建假人 */
    public boolean isRestoreOnStart() { return restoreOnStart; }

    /**
     * 皮肤来源：
     * <ul>
     *   <li>留空 —— 用创建者自己的皮肤（控制台创建时为默认皮肤）</li>
     *   <li>{@code none} —— 默认皮肤</li>
     *   <li>玩家名 —— 用该玩家的皮肤</li>
     * </ul>
     */
    public String getSkinSource() { return skinSource; }

    /** 名字最大长度（Minecraft 玩家名限制为 16） */
    public int getMaxNameLength() { return maxNameLength; }

    /** 每位玩家最多创建几个假人（0 表示不限） */
    public int getMaxPerPlayer() { return maxPerPlayer; }

    /** 持续动作执行间隔（tick，1~200） */
    public int getActionIntervalTicks() { return actionIntervalTicks; }

    /** 持续攻击的实体搜索半径（格） */
    public double getAttackRange() { return attackRange; }

    /** 持续攻击是否攻击玩家 */
    public boolean isAttackPlayers() { return attackPlayers; }

    /** 超级回复间隔（tick） */
    public int getWolverineIntervalTicks() { return wolverineIntervalTicks; }

    /** 自动补充快捷栏检查间隔（tick） */
    public int getReplenishIntervalTicks() { return replenishIntervalTicks; }

    /** 自动钓鱼超时（秒） */
    public int getAutofishTimeoutSeconds() { return autofishTimeoutSeconds; }

    /** 新建假人的默认设置（只读拷贝，防止调用方改坏缓存） */
    public Map<BotSettings, Boolean> getDefaults() {
        return new LinkedHashMap<>(defaults);
    }
}
