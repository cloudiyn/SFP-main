package cn.starfallplain.sfpmain.display;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.module.DisplayConfig;
import io.papermc.paper.scoreboard.numbers.NumberFormat;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Score;
import org.bukkit.scoreboard.Scoreboard;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 右侧计分板信息栏。
 * <p>
 * 标题与每行内容来自 display.yml 的 scoreboard 段（每秒刷新）。每位玩家使用**独立**的
 * {@link Scoreboard} 实例，避免污染服务端主计分板；行内容用
 * {@link Score#customName(Component)} 直接承载（entry 只是内部 key，不显示）。
 * <p>
 * 若服务器上其他插件也占用右侧计分板，会互相覆盖，建议只留一个。
 */
public class ScoreboardManager implements Listener {

    private static final String OBJECTIVE_NAME = "sfp_display";
    /** 行的内部 entry 前缀（不会显示给玩家，显示内容由 customName 决定） */
    private static final String ENTRY_PREFIX = "sfp_line_";

    private final DisplayConfig config;
    private final Map<UUID, Scoreboard> boards = new HashMap<>();
    private final BukkitTask task;

    public ScoreboardManager(SfpMain plugin, ConfigManager configManager) {
        this.config = configManager.display();
        int period = Math.max(1, config.getScoreboardRefreshTicks());
        this.task = Bukkit.getScheduler().runTaskTimer(plugin, this::refresh, 20L, period);
    }

    private void refresh() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            try {
                apply(player);
            } catch (Throwable ignored) {
                // 单个玩家失败不影响其他玩家
            }
        }
    }

    private void apply(Player player) {
        Scoreboard board = boards.computeIfAbsent(player.getUniqueId(), key -> {
            Scoreboard created = Bukkit.getScoreboardManager().getNewScoreboard();
            player.setScoreboard(created);
            return created;
        });

        Component title = DisplayText.render(player, config.getScoreboardTitle());
        Objective objective = board.getObjective(OBJECTIVE_NAME);
        if (objective == null) {
            objective = board.registerNewObjective(OBJECTIVE_NAME, Criteria.DUMMY, title);
            objective.setDisplaySlot(DisplaySlot.SIDEBAR);
            try {
                objective.numberFormat(NumberFormat.blank());   // 隐藏行尾的分数数字
            } catch (Throwable ignored) {
                // 旧版本没有该 API，分数数字会显示，不影响功能
            }
        } else {
            objective.displayName(title);
        }

        List<String> lines = config.getScoreboardLines();
        int count = Math.min(lines.size(), DisplayConfig.MAX_LINES);
        int score = count;
        for (int i = 0; i < count; i++) {
            Score line = objective.getScore(ENTRY_PREFIX + i);
            line.setScore(score--);
            line.customName(DisplayText.render(player, lines.get(i)));
        }
        // 行数变少时清理多余的行
        for (int i = count; i < DisplayConfig.MAX_LINES; i++) {
            String entry = ENTRY_PREFIX + i;
            if (!board.getEntries().contains(entry)) break;
            board.resetScores(entry);
        }
    }

    /** 玩家退出时丢弃他的计分板实例（下线后不再需要） */
    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        boards.remove(event.getPlayer().getUniqueId());
    }

    public void shutdown() {
        if (task != null) task.cancel();
    }
}
