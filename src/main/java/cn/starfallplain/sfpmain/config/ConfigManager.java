package cn.starfallplain.sfpmain.config;

import cn.starfallplain.sfpmain.config.module.BotConfig;
import cn.starfallplain.sfpmain.config.module.ChainConfig;
import cn.starfallplain.sfpmain.config.module.DisplayConfig;
import cn.starfallplain.sfpmain.config.module.CleanConfig;
import cn.starfallplain.sfpmain.config.module.MenuConfig;
import cn.starfallplain.sfpmain.config.module.PunishConfig;
import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import cn.starfallplain.sfpmain.config.module.TrashBinConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置总入口。
 * <p>
 * 文件按功能包拆分，一个包一份配置：
 * <pre>
 *   config.yml       总开关与全局项
 *   menu.yml         菜单（menu 包）
 *   clean.yml        自动扫地（clean 包）
 *   trashbin.yml     垃圾桶（trashbin 包）
 *   teleport.yml     传送（teleport 包：/back、/home、/warp、/tpa）
 *   bot.yml          假人（bot 包）
 *   display.yml      Tab 列表与计分板（display 包）
 *   messages.yml     所有面向玩家的文案
 * </pre>
 * 每个功能文件都有 enabled 开关，关掉即完全停用对应功能
 * （不注册监听器/命令，菜单中也不再出现入口）。
 */
public final class ConfigManager {

    private final JavaPlugin plugin;

    /** 全局配置（config.yml），直接复用 Bukkit 的 config 机制 */
    private final GlobalConfig global;
    private final Messages messages;

    private MenuConfig menuConfig;
    private CleanConfig cleanConfig;
    private ChainConfig chainConfig;
    private TrashBinConfig trashBinConfig;
    private TeleportConfig teleportConfig;
    private BotConfig botConfig;
    private DisplayConfig displayConfig;
    private PunishConfig punishConfig;

    public ConfigManager(JavaPlugin plugin) {
        this.plugin = plugin;
        // 确保 config.yml 存在
        plugin.saveDefaultConfig();
        this.global = new GlobalConfig(plugin);
        this.messages = new Messages(plugin);
        loadAll();
    }

    /** 加载 / 重载全部模块配置 */
    public void loadAll() {
        menuConfig = new MenuConfig(plugin);
        cleanConfig = new CleanConfig(plugin);
        chainConfig = new ChainConfig(plugin);
        trashBinConfig = new TrashBinConfig(plugin);
        teleportConfig = new TeleportConfig(plugin);
        botConfig = new BotConfig(plugin);
        displayConfig = new DisplayConfig(plugin);
        punishConfig = new PunishConfig(plugin);
    }

    /** 重载所有配置（含 messages） */
    public void reload() {
        plugin.reloadConfig();
        global.reload();
        messages.reload();
        loadAll();
    }

    /**
     * 汇总启动日志：哪些功能已启用、哪些被关闭、哪些缺少前置依赖。
     */
    public List<String> describeState(boolean papiAvailable) {
        List<String> lines = new ArrayList<>();

        // 仅处罚模式：其余模块根本没装配，列出它们只会误导运维，故只报处罚相关项。
        if (global.isPunishOnly()) {
            lines.add("  · 工作模式：punish-only（仅处罚）—— 其余模块已跳过装配");
            lines.add(flagLine("处罚系统（/sfpban /sfpmute /sfpkick /sfpwarn）", punishConfig.isEnabled(), true));
            lines.add("  · 管理命令：/sfp（reload / status / db / test）");
            return lines;
        }

        lines.add("  · 工作模式：full（完整）");
        lines.add(flagLine("菜单 /menu", menuConfig.isEnabled(), true));
        lines.add("  · 菜单钟（右键开菜单）："
                + (menuConfig.isEnabled() && menuConfig.isMenuClockEnabled() ? "已启用" : "已关闭（配置）"));
        lines.add(flagLine("自动扫地", cleanConfig.isEnabled(), true));
        lines.add(flagLine("连锁挖矿", chainConfig.isEnabled(), true));
        lines.add(flagLine("垃圾桶", trashBinConfig.isEnabled(), true));
        lines.add(flagLine("传送（/back /home /warp /tpa /rtp）", teleportConfig.isEnabled(), true));
        lines.add("  · 随机传送（/rtp）：" + describeRtp(teleportConfig));
        lines.add(flagLine("假人（/sfp player）", botConfig.isEnabled(), true));
        lines.add(flagLine("Tab 列表", displayConfig.isTabEnabled(), true));
        lines.add(flagLine("计分板", displayConfig.isScoreboardEnabled(), true));
        lines.add(flagLine("处罚系统（/sfpban /sfpmute /sfpkick /sfpwarn）", punishConfig.isEnabled(), true));
        lines.add("  · 扫地倒计时 PAPI 占位符：" + (papiAvailable ? "可用" : "不可用（缺少 PlaceholderAPI）"));
        return lines;
    }

    private String flagLine(String name, boolean enabled, boolean dependencyOk) {
        if (!enabled) return "  · " + name + "：已关闭（配置）";
        if (!dependencyOk) return "  · " + name + "：已启用但缺少前置依赖，实际不可用";
        return "  · " + name + "：已启用";
    }

    /** 随机传送的状态描述：开关 / 世界白名单 / 半径 / 冷却 */
    private String describeRtp(TeleportConfig config) {
        if (!config.isEnabled() || !config.isRtpEnabled()) {
            return "已关闭（配置）";
        }
        java.util.List<String> worlds = config.getRtpWorlds();
        String worldText = (worlds == null || worlds.isEmpty())
                ? "所有世界" : String.join("、", worlds);
        return "已启用（世界：" + worldText + "，范围 ±" + config.getRtpRadius()
                + "，冷却 " + config.getRtpCooldownSeconds() + " 秒）";
    }

    // ==================== 访问器 ====================

    public JavaPlugin getPlugin() {
        return plugin;
    }

    public GlobalConfig global() {
        return global;
    }

    public Messages messages() {
        return messages;
    }

    public MenuConfig menu() {
        return menuConfig;
    }

    public CleanConfig clean() {
        return cleanConfig;
    }

    public ChainConfig chain() {
        return chainConfig;
    }

    public TrashBinConfig trashBin() {
        return trashBinConfig;
    }

    public TeleportConfig teleport() {
        return teleportConfig;
    }

    public BotConfig bot() {
        return botConfig;
    }

    public DisplayConfig display() {
        return displayConfig;
    }

    public PunishConfig punish() {
        return punishConfig;
    }
}
