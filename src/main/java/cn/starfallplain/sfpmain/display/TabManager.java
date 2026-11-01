package cn.starfallplain.sfpmain.display;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.module.DisplayConfig;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * Tab 列表（按 Tab 弹出的玩家列表）的头部 / 底部文字。
 * <p>
 * 内容与刷新频率全部来自 display.yml 的 tab 段；每秒（默认）为所有在线玩家刷新一次。
 */
public class TabManager {

    private final DisplayConfig config;
    private final BukkitTask task;

    public TabManager(SfpMain plugin, ConfigManager configManager) {
        this.config = configManager.display();
        int period = Math.max(1, config.getTabRefreshTicks());
        // 延后 1 秒开始，避开插件启用栈
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, period);
    }

    private void refresh() {
        if (config.getTabHeader().isEmpty() && config.getTabFooter().isEmpty()) return;
        for (Player player : Bukkit.getOnlinePlayers()) {
            // Paper：Tab 头尾走 Adventure 的 Audience#sendPlayerListHeaderAndFooter
            player.sendPlayerListHeaderAndFooter(
                    DisplayText.renderLines(player, config.getTabHeader()),
                    DisplayText.renderLines(player, config.getTabFooter()));
        }
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }
}
