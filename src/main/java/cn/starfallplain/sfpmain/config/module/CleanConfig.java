package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.config.AbstractConfig;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 自动扫地配置（clean.yml）。
 */
public final class CleanConfig extends AbstractConfig {

    private boolean enabled;

    private int cycleSeconds;
    private List<Integer> warnSeconds;

    // 清扫范围
    private List<String> worldWhitelist;
    private List<String> worldBlacklist;

    // 是否把清扫物送入垃圾桶（关闭则直接删除，不经过垃圾桶）
    private boolean toTrashBin;

    // 广播开关
    private boolean broadcastReminders;
    private boolean broadcastResult;

    // 占位符颜色阈值（秒）
    private int safeThresholdSeconds;
    private int warnThresholdSeconds;

    public CleanConfig(JavaPlugin plugin) {
        super(plugin, "clean.yml");
    }

    @Override
    protected void onLoaded() {
        enabled = getBoolean("enabled", true);
        cycleSeconds = getInt("cycle-seconds", 900);
        warnSeconds = getIntegerList("warn-seconds");
        if (warnSeconds.isEmpty()) {
            warnSeconds = List.of(60, 30, 10);
        }
        worldWhitelist = getStringList("worlds.whitelist");
        worldBlacklist = getStringList("worlds.blacklist");
        toTrashBin = getBoolean("to-trashbin", true);
        broadcastReminders = getBoolean("broadcast.reminders", true);
        broadcastResult = getBoolean("broadcast.result", true);
        safeThresholdSeconds = getInt("placeholder.safe-threshold-seconds", 180);
        warnThresholdSeconds = getInt("placeholder.warn-threshold-seconds", 60);
    }

    public boolean isEnabled() { return enabled; }

    public int getCycleSeconds() { return cycleSeconds; }

    public List<Integer> getWarnSeconds() { return warnSeconds; }

    public List<String> getWorldWhitelist() { return worldWhitelist; }

    public List<String> getWorldBlacklist() { return worldBlacklist; }

    public boolean isToTrashBin() { return toTrashBin; }

    public boolean isBroadcastReminders() { return broadcastReminders; }

    public boolean isBroadcastResult() { return broadcastResult; }

    public int getSafeThresholdSeconds() { return safeThresholdSeconds; }

    public int getWarnThresholdSeconds() { return warnThresholdSeconds; }

    /** 判断世界是否参与清扫 */
    public boolean isWorldIncluded(String worldName) {
        if (worldName == null) return false;
        if (worldBlacklist.stream().anyMatch(w -> w.equalsIgnoreCase(worldName))) {
            return false;
        }
        if (worldWhitelist.isEmpty()) {
            return true;
        }
        return worldWhitelist.stream().anyMatch(w -> w.equalsIgnoreCase(worldName));
    }
}
