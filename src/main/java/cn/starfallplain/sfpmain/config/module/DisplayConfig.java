package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.config.AbstractConfig;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

/** Tab 列表与计分板的合并配置。 */
public final class DisplayConfig extends AbstractConfig {
    public static final int MAX_LINES = 15;
    private boolean tabEnabled;
    private int tabRefreshTicks;
    private List<String> tabHeader;
    private List<String> tabFooter;
    private boolean scoreboardEnabled;
    private int scoreboardRefreshTicks;
    private String scoreboardTitle;
    private List<String> scoreboardLines;

    public DisplayConfig(JavaPlugin plugin) { super(plugin, prepare(plugin)); }

    private static String prepare(JavaPlugin plugin) {
        File dir = plugin.getDataFolder();
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，显示配置迁移可能失败：" + dir.getAbsolutePath());
        }
        File merged = new File(dir, "display.yml");
        File tab = new File(dir, "tab.yml");
        File scoreboard = new File(dir, "scoreboard.yml");
        if (merged.exists()) {
            deleteLegacy(plugin, tab);
            deleteLegacy(plugin, scoreboard);
        } else if (tab.isFile() || scoreboard.isFile()) {
                YamlConfiguration out = new YamlConfiguration();
                if (tab.isFile()) {
                    YamlConfiguration old = YamlConfiguration.loadConfiguration(tab);
                    out.set("tab.enabled", old.get("enabled", true));
                    out.set("tab.refresh-ticks", old.get("refresh-ticks", 20));
                    out.set("tab.header", old.getStringList("header"));
                    out.set("tab.footer", old.getStringList("footer"));
                }
                if (scoreboard.isFile()) {
                    YamlConfiguration old = YamlConfiguration.loadConfiguration(scoreboard);
                    out.set("scoreboard.enabled", old.get("enabled", true));
                    out.set("scoreboard.refresh-ticks", old.get("refresh-ticks", 20));
                    out.set("scoreboard.title", old.getString("title"));
                    out.set("scoreboard.lines", old.getStringList("lines"));
                }
                try { out.save(merged); if (tab.isFile()) Files.deleteIfExists(tab.toPath()); if (scoreboard.isFile()) Files.deleteIfExists(scoreboard.toPath()); }
                catch (IOException e) { plugin.getLogger().warning("合并显示配置失败：" + e.getMessage()); }
        }
        return "display.yml";
    }

    private static void deleteLegacy(JavaPlugin plugin, File file) {
        if (!file.isFile()) return;
        try {
            Files.deleteIfExists(file.toPath());
        } catch (IOException e) {
            plugin.getLogger().warning("无法删除旧显示配置 " + file.getName() + "：" + e.getMessage());
        }
    }

    @Override
    protected void onLoaded() {
        tabEnabled = getBoolean("tab.enabled", true);
        tabRefreshTicks = Math.max(1, getInt("tab.refresh-ticks", 20));
        tabHeader = getStringList("tab.header");
        tabFooter = getStringList("tab.footer");
        scoreboardEnabled = getBoolean("scoreboard.enabled", true);
        scoreboardRefreshTicks = Math.max(1, getInt("scoreboard.refresh-ticks", 20));
        scoreboardTitle = getString("scoreboard.title", "<gradient:aqua:light_purple><b>星落平原</b></gradient>");
        scoreboardLines = getStringList("scoreboard.lines");
        if (scoreboardLines.size() > MAX_LINES) scoreboardLines = List.copyOf(scoreboardLines.subList(0, MAX_LINES));
    }
    public boolean isTabEnabled() { return tabEnabled; }
    public int getTabRefreshTicks() { return tabRefreshTicks; }
    public List<String> getTabHeader() { return tabHeader; }
    public List<String> getTabFooter() { return tabFooter; }
    public boolean isScoreboardEnabled() { return scoreboardEnabled; }
    public int getScoreboardRefreshTicks() { return scoreboardRefreshTicks; }
    public String getScoreboardTitle() { return scoreboardTitle; }
    public List<String> getScoreboardLines() { return scoreboardLines; }
}
