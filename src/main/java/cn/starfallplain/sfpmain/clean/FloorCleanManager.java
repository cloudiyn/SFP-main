package cn.starfallplain.sfpmain.clean;

import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.CleanConfig;
import cn.starfallplain.sfpmain.trashbin.TrashBinManager;
import cn.starfallplain.sfpmain.util.TimeUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Item;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 自动扫地。周期、提醒点、世界范围、是否入垃圾桶等来自 clean.yml，
 * 文案来自 messages.yml 的 clean.*。
 */
public class FloorCleanManager {

    private final JavaPlugin plugin;
    private final CleanConfig config;
    private final Messages messages;
    private final TrashBinManager trashBinManager;
    private final int cycleSeconds;
    private final List<Integer> warnSeconds;
    private int remainingSeconds;
    private final Set<Integer> reminded = new HashSet<>();

    public FloorCleanManager(JavaPlugin plugin, ConfigManager configManager, TrashBinManager trashBinManager) {
        this.plugin = plugin;
        this.config = configManager.clean();
        this.messages = configManager.messages();
        this.trashBinManager = trashBinManager;
        this.cycleSeconds = Math.max(60, config.getCycleSeconds()); // 至少 60 秒
        this.warnSeconds = config.getWarnSeconds();
        this.remainingSeconds = this.cycleSeconds;
        startCountdown();
    }

    public CleanConfig getConfig() {
        return config;
    }

    private void startCountdown() {
        new BukkitRunnable() {
            @Override
            public void run() {
                // 到达提醒点时广播
                for (int remind : warnSeconds) {
                    if (remainingSeconds == remind && !reminded.contains(remind)) {
                        reminded.add(remind);
                        if (config.isBroadcastReminders()) {
                            broadcastReminder(remind);
                        }
                    }
                }

                remainingSeconds--;

                if (remainingSeconds <= 0) {
                    performClean();
                    remainingSeconds = cycleSeconds;
                    reminded.clear();
                }
            }
        }.runTaskTimer(plugin, 20L, 20L); // 每秒执行
    }

    /**
     * 执行一次清扫。
     * <p>
     * 世界范围内所有 {@link Item} 掉落物一律清除：有自定义名称的（改名物品）不再特殊对待
     * —— 2.11.0 起取消了 {@code skip-named-items} 开关，改名物品与普通掉落物同待遇，
     * 由 {@code to-trashbin} 决定是进垃圾桶（可取回）还是直接销毁。
     */
    private void performClean() {
        List<ItemStack> collected = new ArrayList<>();
        int count = 0;
        for (World world : Bukkit.getWorlds()) {
            if (!config.isWorldIncluded(world.getName())) continue;
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Item itemEntity) {
                    ItemStack stack = itemEntity.getItemStack();
                    if (stack != null && !stack.getType().isAir()) {
                        collected.add(stack);
                    }
                    entity.remove();
                    count++;
                }
            }
        }

        boolean toTrash = config.isToTrashBin() && trashBinManager != null;
        if (toTrash) {
            // 垃圾桶仅保留至下次扫地前：先清空旧物品，再收入本次清扫的掉落物
            trashBinManager.clear();
            if (!collected.isEmpty()) {
                trashBinManager.addAll(collected);
            }
            trashBinManager.save();
        }

        final int total = count;
        final int trashSize = trashBinManager != null ? trashBinManager.size() : 0;
        plugin.getLogger().info("扫地完成，收集 " + total + " 个掉落物"
                + (toTrash ? "到垃圾桶（旧物品已清空，当前共 " + trashSize + " 件）" : "并直接删除"));

        if (config.isBroadcastResult()) {
            broadcastResult(total, toTrash);
        }
    }

    private void broadcastResult(int total, boolean toTrash) {
        String cycleText = TimeUtil.formatTime(cycleSeconds);
        Map<String, String> ph = new HashMap<>();
        ph.put("count", String.valueOf(total));
        ph.put("time", cycleText);

        if (!toTrash) {
            String text = Messages.apply(messages.raw("clean.result-deleted",
                    "<!i><green>[扫地] 已清理 {count} 个掉落物。</green> <gray>下次清扫将在 {time} 后。</gray>"), ph);
            Bukkit.broadcast(Messages.deserialize(text));
            return;
        }

        String text = Messages.apply(messages.raw("clean.result",
                "<!i><green>[扫地] 已清扫 {count} 个掉落物并收入垃圾桶。</green> "
                        + "<yellow>请在下次清扫前打开垃圾桶取回重要物品！</yellow> "
                        + "<gray>下次清扫将在 {time} 后。</gray>"), ph);
        String buttonText = messages.raw("clean.result-button",
                "<!i><yellow><click:run_command:'/trashbin'>[打开垃圾桶]</click></yellow>");
        String hoverText = messages.raw("clean.result-button-hover",
                "<!i><gray>点击打开垃圾桶取回物品</gray>");

        Component message = Messages.deserialize(text)
                .append(Messages.deserialize("<!i> "))
                .append(Messages.deserialize(buttonText)
                        .clickEvent(ClickEvent.runCommand("/trashbin"))
                        .hoverEvent(HoverEvent.showText(Messages.deserialize(hoverText))));
        Bukkit.broadcast(message);
    }

    private void broadcastReminder(int seconds) {
        Map<String, String> ph = new HashMap<>();
        ph.put("seconds", String.valueOf(seconds));
        String text = Messages.apply(messages.raw("clean.reminder",
                "<!i><yellow>[扫地] 还有 {seconds} 秒将清扫地面掉落物，请及时拾取！</yellow>"), ph);
        Bukkit.broadcast(Messages.deserialize(text));
    }

    public int getSecondsUntilNextClean() {
        return remainingSeconds;
    }

    /**
     * 格式化剩余时间：x分x秒（小于一分钟时只需 x秒）
     */
    public String formatTime() {
        return TimeUtil.formatTime(remainingSeconds);
    }

    /**
     * 返回带颜色的 MiniMessage 文本（供 PAPI 占位符返回带颜色文本）。
     * 阈值来自 clean.yml 的 placeholder.*。
     */
    public String getColoredTimeMini() {
        int sec = remainingSeconds;
        String time = TimeUtil.formatTime(sec);
        String color;
        if (sec > config.getSafeThresholdSeconds()) {
            color = "green";
        } else if (sec > config.getWarnThresholdSeconds()) {
            color = "yellow";
        } else {
            color = "red";
        }
        return "<" + color + ">" + time + "</" + color + ">";
    }
}
