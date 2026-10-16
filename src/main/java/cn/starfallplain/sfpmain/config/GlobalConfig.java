package cn.starfallplain.sfpmain.config;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * 全局配置（config.yml）：与具体功能包无关的通用项。
 */
public final class GlobalConfig {

    /**
     * 工作模式。
     * <ul>
     *   <li>{@link #MODE_FULL} —— 完整模式：装配全部功能模块（默认）。</li>
     *   <li>{@link #MODE_PUNISH_ONLY} —— 仅处罚模式：只启动处罚系统 + {@code /sfp} 管理命令，
     *       其余模块（菜单 / 传送 / 扫地 / 垃圾桶 / 椅子 / 假人 / Tab / 计分板）一律不装配。
     *       群组服里用于「只跑处罚的子服」，避免重复加载与权限冲突。</li>
     * </ul>
     */
    private static final String MODE_FULL = "full";
    private static final String MODE_PUNISH_ONLY = "punish-only";

    private final JavaPlugin plugin;

    // 调试模式：开启后输出更详细的日志
    private boolean debug;

    // 工作模式：full / punish-only（无法识别的值按 full 处理并记 warning）
    private boolean punishOnly;

    public GlobalConfig(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        this.debug = plugin.getConfig().getBoolean("debug", false);

        String raw = plugin.getConfig().getString("mode", MODE_FULL);
        String mode = raw == null ? MODE_FULL : raw.trim().toLowerCase(java.util.Locale.ROOT);
        switch (mode) {
            case MODE_PUNISH_ONLY -> this.punishOnly = true;
            case MODE_FULL -> this.punishOnly = false;
            default -> {
                plugin.getLogger().warning("config.yml 的 mode 值 '" + raw
                        + "' 无法识别（应为 full 或 punish-only），本次按 full 处理。");
                this.punishOnly = false;
            }
        }
    }

    public boolean isDebug() { return debug; }

    /** 是否处于「仅处罚」工作模式（群组服的处罚子服用） */
    public boolean isPunishOnly() { return punishOnly; }

    /** 当前模式的展示名，用于启动日志 */
    public String modeName() {
        return punishOnly ? "punish-only（仅处罚）" : "full（完整）";
    }

    public void debug(String message) {
        if (debug) plugin.getLogger().info("[DEBUG] " + message);
    }
}
