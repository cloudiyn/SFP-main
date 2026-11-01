package cn.starfallplain.sfpmain.debug;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.clean.FloorCleanManager;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.ChainConfig;
import cn.starfallplain.sfpmain.config.module.MenuConfig;
import cn.starfallplain.sfpmain.menu.MenuClockManager;
import cn.starfallplain.sfpmain.menu.MenuHolder;
import cn.starfallplain.sfpmain.menu.MenuManager;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.db.Database;
import cn.starfallplain.sfpmain.trashbin.TrashBinManager;
import cn.starfallplain.sfpmain.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * 功能自检（{@code /sfp test}）：把配置、菜单、数据层、传送内容、各模块运行态过一遍，
 * 逐项给出 <b>[OK] / [注意] / [错误]</b> 并汇总。
 * <p>
 * 设计原则：
 * <ul>
 *   <li>只读为主 —— 唯一会写数据的是数据层的「增删改查回归」，用哨兵 UUID + 哨兵名称，
 *       且无论成功失败都会清理，不碰真实玩家数据。</li>
 *   <li>能查出「编译期看不出来、只有真跑才会踩」的问题：按钮没绑动作、槽位越界、
 *       表结构版本不对、家/传送点指向已删除的世界、文案键缺失、配置数值越界。</li>
 *   <li>输出写死在本类里（诊断条目多且动态，不进 messages.yml），门槛为 {@code sfpmenu.admin}。</li>
 * </ul>
 */
public final class SelfTest {

    /** 每张表期望的列（schema 漂移检测） */
    private static final Map<String, String[]> EXPECTED_COLUMNS = new LinkedHashMap<>();

    static {
        EXPECTED_COLUMNS.put("homes", new String[]{
                "player_uuid", "home_name", "world", "x", "y", "z", "yaw", "pitch", "created_at"});
        EXPECTED_COLUMNS.put("warps", new String[]{
                "warp_name", "world", "x", "y", "z", "yaw", "pitch", "created_at"});
        EXPECTED_COLUMNS.put("last_locations", new String[]{
                "player_uuid", "world", "x", "y", "z", "yaw", "pitch", "updated_at"});
    }

    /** messages.yml 里必须存在的键（缺了不会报错，只会静默退回代码内置文案，所以专门查一遍） */
    private static final String[] REQUIRED_MESSAGE_KEYS = {
            "common.no-permission", "common.player-only", "common.feature-disabled", "common.back-to-menu",
            "menu.head-name",
            "menu.elytra-success", "menu.elytra-not-enough", "menu.elytra-misconfigured",
            "trashbin.title", "trashbin.disabled", "trashbin.prev", "trashbin.next",
            "clean.reminder", "clean.result",
            "clean.enabled", "clean.disabled", "clean.database-error", "clean.world-not-found",
            "clean.invalid-coordinate", "clean.exception-added", "clean.exception-removed",
            "clean.exception-unchanged",
            "home.teleport-success", "home.set-success", "home.not-found",
            "warp.teleport-success", "warp.set-success", "warp.not-found",
            "back.no-location", "back.success",
            "rtp.searching", "rtp.success", "rtp.cooldown", "rtp.world-not-allowed", "rtp.failed",
            "teleport.delayed", "teleport.cancelled", "teleport.cooldown", "teleport.world-missing",
            "tpa.request-to", "tpa.request-here", "tpa.gui-title", "tpa.no-request",
            "punish.warn-received", "punish.warn-applied", "punish.kick-applied",
            "punish.ban-applied", "punish.mute-applied", "punish.unban-applied", "punish.unmute-applied",
    };

    /**
     * 核心键之后**新增**的文案键（按版本累积，只增不删）。
     * <p>
     * 与 {@link #REQUIRED_MESSAGE_KEYS} 分开报的原因：{@code mergeDefaults()} 只补缺失键、
     * **不覆盖已存在的键**，所以老服的 messages.yml 里这些新键**不会自动出现**，缺失时会退回
     * 代码内置的同款文案 —— 功能正常，但运维会以为「我明明在 messages.yml 里改过这句话」。
     * 所以单独提示一次，让人知道该手工补键或删掉文件重新生成。
     */
    private static final String[] RECENTLY_ADDED_MESSAGE_KEYS = {
            // ---- 2.11.0：dialogUI 列表/选择框文案 ----
            "dialogui.entry-name", "dialogui.entry-hover-location",
            "dialogui.home-action-title", "dialogui.home-action-body",
            "dialogui.home-teleport", "dialogui.home-teleport-hover",
            "dialogui.home-delete", "dialogui.home-delete-hover",
            "dialogui.tpa-entry-hover", "dialogui.tpa-direction-title", "dialogui.tpa-direction-body",
            "dialogui.tpa-to", "dialogui.tpa-to-hover", "dialogui.tpa-here", "dialogui.tpa-here-hover",
            // ---- 2.11.0：处罚命令的用法与提示（此前硬编码在代码里）----
            "punish.usage-check", "punish.usage-warn", "punish.usage-kick", "punish.usage-ban",
            "punish.usage-mute", "punish.usage-unban", "punish.usage-unmute",
            "punish.time-format-tip", "punish.bad-time", "punish.revoke-tip",
            "punish.not-banned", "punish.not-muted", "punish.offline", "punish.unknown-player",
            "punish.no-record", "punish.no-active-punishment", "punish.check-header",
            "punish.check-footer", "punish.check-active-none", "punish.check-active-entry",
            "punish.log-line",
            // ---- 2.11.0：假人坐标用法 ----
            "bot.usage-coords",
            // ---- 2.13.0：连锁挖矿 ----
            "chain.broken", "chain.partial", "chain.not-enough-food",
            // ---- 2.12.0：假人重写为 /sfp player（对齐 minecraft-fakeplayer）----
            "bot.spawn-usage", "bot.select-usage", "bot.select-player-only",
            "bot.selection-none", "bot.selection-current", "bot.no-selection",
            "bot.status-header", "bot.status-offline", "bot.status-online", "bot.status-actions",
            "bot.status-settings-header", "bot.status-settings-entry", "bot.status-owner",
            "bot.offline", "bot.player-only", "bot.distance", "bot.distance-other-world",
            "bot.drop-empty", "bot.dropped", "bot.dropped-stack", "bot.dropped-inv",
            "bot.swapped", "bot.held", "bot.hold-usage",
            "bot.teleported-to", "bot.teleported-here", "bot.teleported-swap",
            "bot.exp-none", "bot.exp-transferred",
            "bot.cmd-empty", "bot.cmd-done", "bot.cmd-failed", "bot.cmd-usage",
            "bot.skin-usage", "bot.skin-not-found", "bot.skin-failed", "bot.skin-copied",
            "bot.set-usage", "bot.set-done",
            "bot.config-header", "bot.config-entry", "bot.config-player-only",
            "bot.config-set-done", "bot.config-cleared", "bot.config-clear-none", "bot.config-usage",
            "bot.action-toggled", "bot.action-none", "bot.action-stopped", "bot.action-failed",
            "bot.jump", "bot.sneak-toggled", "bot.sprint-toggled",
            "bot.looked", "bot.look-usage", "bot.turned", "bot.turn-usage",
            "bot.ridden", "bot.dismounted", "bot.ride-none", "bot.ride-failed",
            "bot.sleep", "bot.wakeup",
            "bot.respawned", "bot.respawn-alive", "bot.respawn-failed",
            // ---- 2.16.1：命令提示统一（未知子命令/带权限节点/用法兜底）----
            "common.no-permission-with-node", "common.unknown-subcommand-input",
            "common.unknown-subcommand-suggest", "common.unknown-subcommand-hint",
            "bot.config-set-usage", "bot.sneak-usage", "bot.sprint-usage",
    };

    /** 代码里实际用到的权限节点（应全部在 paper-plugin.yml 的 permissions 段声明） */
    private static final String[] USED_PERMISSIONS = {
            "sfpmenu.player", "sfpmenu.teleport", "sfpmenu.bot", "sfpmenu.bot.killall", "sfpmenu.admin",
            "sfpmenu.clean.manage",
            "sfpmenu.home.bypass-limit", "sfpmenu.warp.set", "sfpmenu.warp.delete",
            "sfpmenu.punish.check", "sfpmenu.punish.warn", "sfpmenu.punish.kick",
            "sfpmenu.punish.ban", "sfpmenu.punish.mute", "sfpmenu.punish.unban", "sfpmenu.punish.unmute",
    };

    /** 处罚数据库期望的表与列（schema 漂移检测） */
    private static final Map<String, String[]> EXPECTED_PUNISH_COLUMNS = new LinkedHashMap<>();

    static {
        EXPECTED_PUNISH_COLUMNS.put("players", new String[]{"uuid", "name", "last_seen"});
        EXPECTED_PUNISH_COLUMNS.put("punishments", new String[]{
                "punishment_id", "player_uuid", "player_name", "type", "reason", "operator",
                "created_at", "expire_at"});
        EXPECTED_PUNISH_COLUMNS.put("punishment_logs", new String[]{
                "log_id", "punishment_id", "player_uuid", "player_name", "type", "reason", "operator",
                "created_at", "expire_at", "action", "action_at"});
    }

    private SelfTest() {
    }

    /** 运行全部自检并输出报告 */
    public static void run(SfpMain plugin, CommandSender sender) {
        Report r = new Report(sender);
        DbDebug.send(sender, "<dark_gray>========== 星落平原 自检 ==========</dark_gray>");

        // 仅处罚模式：非处罚模块根本没装配，跳过它们的检查，避免刷出一屏「未启用」误导运维。
        if (plugin.getConfigManager().global().isPunishOnly()) {
            DbDebug.send(sender, "<yellow>工作模式：punish-only（仅处罚），只检查处罚系统。</yellow>");
            checkConfig(plugin, r);
            checkPunish(plugin, r);
            DbDebug.send(sender, "<dark_gray>------------------------------------</dark_gray>");
            DbDebug.send(sender, "<gray>结果：</gray><green>通过 " + r.ok + "</green>　<yellow>注意 "
                    + r.warn + "</yellow>　<red>错误 " + r.fail + "</red>");
            return;
        }

        checkConfig(plugin, r);
        checkMenu(plugin, r, sender);
        checkStorage(plugin, r);
        checkTeleportData(plugin, r);
        checkBots(plugin, r);
        checkChain(plugin, r);
        checkPunish(plugin, r);
        checkModules(plugin, r, sender);
        DbDebug.send(sender, "<dark_gray>------------------------------------</dark_gray>");
        DbDebug.send(sender, "<gray>结果：</gray><green>通过 " + r.ok + "</green>　<yellow>注意 "
                + r.warn + "</yellow>　<red>错误 " + r.fail + "</red>");
    }

    // ==================== 配置 ====================

    private static void checkConfig(SfpMain plugin, Report r) {
        r.section("配置");
        ConfigManager cm = plugin.getConfigManager();

        Map<String, Boolean> loaded = new LinkedHashMap<>();
        loaded.put("config.yml", cm.global() != null);
        loaded.put("messages.yml", cm.messages() != null);
        loaded.put("menu.yml", cm.menu() != null && cm.menu().raw() != null);
        loaded.put("clean.yml", cm.clean() != null && cm.clean().raw() != null);
        loaded.put("trashbin.yml", cm.trashBin() != null && cm.trashBin().raw() != null);
        loaded.put("teleport.yml", cm.teleport() != null && cm.teleport().raw() != null);
        loaded.put("punish.yml", cm.punish() != null && cm.punish().raw() != null);
        loaded.put("bot.yml", cm.bot() != null && cm.bot().raw() != null);
        loaded.put("chain.yml", cm.chain() != null && cm.chain().raw() != null);
        loaded.put("display.yml", cm.display() != null && cm.display().raw() != null);
        List<String> notLoaded = new ArrayList<>();
        loaded.forEach((name, okFlag) -> {
            if (!okFlag) notLoaded.add(name);
        });
        if (notLoaded.isEmpty()) {
            r.ok(loaded.size() + " 个配置文件均已加载");
        } else {
            r.fail("以下配置未能加载：" + String.join(", ", notLoaded));
        }

        var tp = cm.teleport();
        List<String> badTp = new ArrayList<>();
        if (tp.getDelaySeconds() < 0) badTp.add("teleport.delay-seconds 为负");
        if (tp.getHomeSetCooldownSeconds() < 0) badTp.add("home.set-cooldown-seconds 为负");
        if (tp.getHomeTeleportCooldownSeconds() < 0) badTp.add("home.teleport-cooldown-seconds 为负");
        if (tp.getWarpTeleportCooldownSeconds() < 0) badTp.add("warp.teleport-cooldown-seconds 为负");
        if (tp.getHomeMaxHomes() < -1) badTp.add("home.max-homes 小于 -1");
        if (tp.getSafeSearchDistance() <= 0) badTp.add("teleport.safe-search-distance 应大于 0");
        if (tp.getDbFile() == null || tp.getDbFile().isBlank()) badTp.add("database.file 为空");
        if (tp.isRtpEnabled()) {
            if (tp.getRtpRadius() < 1) badTp.add("rtp.radius 应大于 0");
            if (tp.getRtpMaxAttempts() < 1) badTp.add("rtp.max-attempts 应大于 0");
            if (tp.getRtpMaxVerticalSearch() < 1) badTp.add("rtp.max-vertical-search 应大于 0");
        }
        if (badTp.isEmpty()) r.ok("teleport.yml 数值合法");
        else r.warn("teleport.yml：" + String.join("；", badTp));

        // 随机传送：白名单里的世界是否真实存在
        if (tp.isRtpEnabled()) {
            var rtpWorlds = tp.getRtpWorlds();
            if (rtpWorlds != null && !rtpWorlds.isEmpty()) {
                List<String> missing = new ArrayList<>();
                for (String name : rtpWorlds) {
                    if (Bukkit.getWorld(name) == null) missing.add(name);
                }
                if (missing.isEmpty()) {
                    r.ok("rtp.worlds 白名单世界均已加载：" + String.join("、", rtpWorlds));
                } else {
                    r.warn("rtp.worlds 中这些世界不存在或未加载（该世界无法随机传送）："
                            + String.join("、", missing));
                }
            } else {
                r.warn("rtp.worlds 为空，所有世界都允许随机传送（如需限制请填入世界名）");
            }
        }

        var clean = cm.clean();
        if (clean.getCycleSeconds() < 60) {
            r.warn("clean.cycle-seconds = " + clean.getCycleSeconds() + "，小于 60 会被代码强制提到 60");
        } else {
            r.ok("clean.cycle-seconds = " + clean.getCycleSeconds() + " 秒");
        }
        List<Integer> warns = clean.getWarnSeconds();
        if (warns == null || warns.isEmpty()) {
            r.warn("clean.warn-seconds 为空，清扫前不会有任何提醒");
        } else {
            List<Integer> invalid = warns.stream()
                    .filter(s -> s <= 0 || s >= clean.getCycleSeconds()).toList();
            if (invalid.isEmpty()) r.ok("clean.warn-seconds = " + warns + "，都在周期内");
            else r.warn("clean.warn-seconds 中这些值不在 (0, 周期) 内，永远不会触发：" + invalid);
        }

        List<String> missingKeys = new ArrayList<>();
        for (String key : REQUIRED_MESSAGE_KEYS) {
            if (!cm.messages().has(key)) missingKeys.add(key);
        }
        if (missingKeys.isEmpty()) {
            r.ok("messages.yml 关键文案键齐全（查了 " + REQUIRED_MESSAGE_KEYS.length + " 个）");
        } else {
            r.warn("你的 messages.yml 缺少 " + missingKeys.size() + " 个关键键（会退回代码内置文案）："
                    + String.join(", ", missingKeys));
        }

        // 新增键：mergeDefaults() 不会把新键写进已存在的 messages.yml（只补缺失键、不覆盖已有键），
        // 缺了新键照样能跑（退回内置文案），但改文案的人会找不到键，所以单独报一次
        List<String> missingNew = new ArrayList<>();
        for (String key : RECENTLY_ADDED_MESSAGE_KEYS) {
            if (!cm.messages().has(key)) missingNew.add(key);
        }
        if (missingNew.isEmpty()) {
            r.ok("messages.yml 已包含近期新增的文案键（" + RECENTLY_ADDED_MESSAGE_KEYS.length + " 个）");
        } else {
            r.warn("你的 messages.yml 缺少 " + missingNew.size() + " 个近期新增的键"
                    + "（老配置不会被自动补齐，缺失时用代码内置同款文案）："
                    + String.join(", ", missingNew)
                    + " —— 可手工补键，或备份后删掉 messages.yml 让插件重新生成");
        }

        // 已被删除的「死键」：老配置里可能还留着，不生效但容易被误以为在起作用
        List<String> staleKeys = new ArrayList<>();
        // ---- 2.11.0 删除 ----
        if (cm.clean().isSet("skip-named-items")) staleKeys.add("clean.yml 的 skip-named-items");
        if (cm.punish().isSet("messages.join-warn-notice")) {
            staleKeys.add("punish.yml 的 messages.join-warn-notice");
        }
        // ---- 2.5.0 删除（见 features.md §11）----
        if (cm.clean().isSet("worlds.only-loaded-chunks")) {
            staleKeys.add("clean.yml 的 worlds.only-loaded-chunks");
        }
        if (cm.trashBin().isSet("global")) staleKeys.add("trashbin.yml 的 global");
        if (cm.teleport().isSet("allow-cross-world") || cm.teleport().isSet("teleport.allow-cross-world")) {
            staleKeys.add("teleport.yml 的 allow-cross-world");
        }
        if (plugin.getConfig().isSet("currency-name")) staleKeys.add("config.yml 的 currency-name");
        if (staleKeys.isEmpty()) {
            r.ok("配置里没有已删除的失效键");
        } else {
            r.warn("这些键已被删除（留着无害、也完全不起作用，建议删掉）：" + String.join("、", staleKeys));
        }

        // 扫地 × 垃圾桶的组合：to-trashbin 开着但垃圾桶模块关掉 = 清扫物直接销毁且不可取回
        if (cm.clean().isEnabled() && cm.clean().isToTrashBin() && !cm.trashBin().isEnabled()) {
            r.warn("clean.yml 的 to-trashbin 为 true，但垃圾桶模块已关闭（trashbin.yml 的 enabled=false）："
                    + "清扫物会被直接删除且无法取回 —— 要么打开垃圾桶，要么把 to-trashbin 改成 false");
        }

        checkPermissions(plugin, r);

        // 音效名有效性：resolve 返回 null 就是「无声」，是静默 bug 高发处
        List<String> badSounds = new ArrayList<>();
        checkSound(cm.teleport().getWaitStartSound(), "teleport.sounds.start", badSounds);
        checkSound(cm.teleport().getWaitTickSound(), "teleport.sounds.tick", badSounds);
        checkSound(cm.teleport().getTeleportSound(), "teleport.sounds.teleport", badSounds);
        checkSound(cm.trashBin().getTakeSound(), "trashbin.sounds.take", badSounds);
        if (badSounds.isEmpty()) {
            r.ok("配置的音效名全部有效");
        } else {
            r.fail("以下音效名无法解析（会无声）：" + String.join(", ", badSounds));
        }
    }

    /**
     * 权限节点体检：代码里 {@code hasPermission(...)} 用到的节点必须在本插件的
     * {@code paper-plugin.yml} 里声明过。
     * <p>
     * 未声明的权限对非 OP 玩家等于「未设置」，没有默认值可依 —— 后果是「命令看得见、
     * 永远没权限」，除了现场试一次很难发现，所以在这里静态查出来。
     */
    private static void checkPermissions(SfpMain plugin, Report r) {
        List<String> declared = new ArrayList<>();
        try {
            for (var permission : plugin.getPluginMeta().getPermissions()) {
                declared.add(permission.getName());
            }
        } catch (Throwable t) {
            r.warn("读取插件权限声明失败，跳过权限检查：" + t);
            return;
        }
        List<String> missing = new ArrayList<>();
        for (String used : USED_PERMISSIONS) {
            if (!declared.contains(used)) missing.add(used);
        }
        if (missing.isEmpty()) {
            r.ok("代码用到的权限节点都已在 paper-plugin.yml 声明（" + USED_PERMISSIONS.length + " 个）");
        } else {
            r.fail("这些权限节点代码里在用、paper-plugin.yml 却没声明（非 OP 玩家会永远没权限）："
                    + String.join(", ", missing));
        }
    }

    /** 留空 = 静音（合法）；非空但解析失败 = 有问题 */
    private static void checkSound(String name, String label, List<String> bad) {
        if (name == null || name.isBlank()) return;
        if (SoundUtil.resolve(name) == null) {
            bad.add(label + "(" + name + ")");
        }
    }

    // ==================== 菜单 ====================

    private static void checkMenu(SfpMain plugin, Report r, CommandSender sender) {
        r.section("菜单");
        ConfigManager cm = plugin.getConfigManager();
        MenuConfig menu = cm.menu();

        if (!menu.isEnabled()) {
            r.warn("menu.yml 的 enabled 为 false，/menu 会提示未启用");
            return;
        }
        int size = menu.getSize();
        r.ok("菜单尺寸 " + menu.getRows() + " 行（" + size + " 格）");

        List<String> outOfRange = new ArrayList<>();
        List<String> unbound = new ArrayList<>();
        List<String> infoOnly = new ArrayList<>();
        for (MenuConfig.Button b : menu.getButtons().values()) {
            if (b.getSlot() < 0 || b.getSlot() >= size) {
                outOfRange.add(b.getId() + "(" + b.getSlot() + ")");
            }
            if (b.isVisible() && MenuManager.resolveAction(b, menu) == null) {
                // 有说明文案的按钮属于「纯展示」，是设计如此；两者都没有才算真问题
                if (b.getLore() != null && !b.getLore().isEmpty()) infoOnly.add(b.getId());
                else unbound.add(b.getId());
            }
        }
        if (outOfRange.isEmpty()) r.ok("所有按钮槽位都在 0~" + (size - 1) + " 内");
        else r.fail("按钮槽位越界（不会出现在菜单里）：" + String.join(", ", outOfRange));
        if (unbound.isEmpty()) r.ok("启用的按钮都绑定了动作（缺少动作的只作说明展示）");
        else r.warn("这些按钮既没绑动作也没有说明文案，点了完全没反应：" + String.join(", ", unbound));

        // 槽位冲突：slotActions 是 Map，两个按钮抢同一格时**后一个静默覆盖前一个**，
        // 界面上少一个按钮却没有任何报错 —— 手改 menu.yml 槽位时最容易踩的坑
        Map<Integer, String> occupied = new LinkedHashMap<>();
        List<String> conflicts = new ArrayList<>();
        for (MenuConfig.Button b : MenuManager.activeButtons(menu, cm)) {
            String previous = occupied.put(b.getSlot(), b.getId());
            if (previous != null) {
                conflicts.add("槽位 " + b.getSlot() + "：" + previous + " 与 " + b.getId());
            }
        }
        if (conflicts.isEmpty()) {
            r.ok("没有两个按钮抢同一格（" + occupied.size() + " 个按钮各占一格）");
        } else {
            r.fail("按钮槽位重复，排在后面的会盖掉前面的（界面上只显示一个）："
                    + String.join("；", conflicts));
        }
        // 玩家头颅与按钮同格：createMainMenu 先放头像、再放按钮 → 按钮会把头像盖掉
        if (menu.isShowPlayerHead() && occupied.containsKey(menu.getPlayerHeadSlot())) {
            r.warn("玩家头颅槽位 " + menu.getPlayerHeadSlot() + " 与按钮 "
                    + occupied.get(menu.getPlayerHeadSlot()) + " 冲突：按钮会盖掉顶栏头像");
        }
        if (!infoOnly.isEmpty()) {
            DbDebug.send(sender, "<dark_gray>  · 仅作说明展示（有 lore、无动作）："
                    + String.join(", ", infoOnly) + "</dark_gray>");
        }

        Map<Integer, String> actions = MenuManager.resolveSlotActions(menu, cm);
        DbDebug.send(sender, "<gray>实际生效的按钮 " + actions.size() + " 个：</gray>");
        for (Map.Entry<Integer, String> e : actions.entrySet()) {
            DbDebug.send(sender, "<dark_gray>  · 槽位 </dark_gray><white>" + e.getKey() + "</white>"
                    + "<dark_gray> → </dark_gray><gray>" + describeAction(e.getValue()) + "</gray>");
        }

        checkElytraExchange(plugin, menu, r);
        checkLoreDrift(plugin, menu, r);

        if (sender instanceof Player player) {
            List<String> lack = new ArrayList<>();
            if (!player.hasPermission("sfpmenu.player")) lack.add("sfpmenu.player");
            if (!player.hasPermission("sfpmenu.teleport")) lack.add("sfpmenu.teleport");
            String openPerm = menu.getOpenPermission();
            if (openPerm != null && !openPerm.isBlank() && !player.hasPermission(openPerm)) {
                lack.add(openPerm);
            }
            if (lack.isEmpty()) r.ok("你的权限可以完整使用菜单");
            else r.warn("你缺少权限：" + String.join(", ", lack));
        } else {
            DbDebug.send(sender, "<gray>（控制台执行，跳过权限检查）</gray>");
        }

        // dialogUI 构建冒烟：跑一遍 Dialog API 调用链，验证在服务器环境不抛异常
        try {
            if (cn.starfallplain.sfpmain.ui.DialogMenu.build(plugin) != null) {
                r.ok("dialogUI 主菜单构建正常（" + actions.size() + " 个按钮）");
            } else {
                r.warn("dialogUI 主菜单没有可用按钮");
            }
        } catch (Throwable t) {
            r.fail("dialogUI 主菜单构建失败：" + t);
        }

        checkMenuClock(plugin, r);
    }

    /** 菜单钟：合成配方是否已注册、物品能否构建并被自身识别 */
    private static void checkMenuClock(SfpMain plugin, Report r) {
        MenuConfig menu = plugin.getConfigManager().menu();
        if (!menu.isMenuClockEnabled()) {
            r.warn("菜单钟已按配置关闭（menu-clock.enabled=false）");
            return;
        }
        MenuClockManager manager = plugin.getMenuClockManager();
        if (manager == null) {
            r.warn("菜单钟管理器未创建（主菜单可能被关闭）");
            return;
        }
        r.ok("菜单钟材质 " + menu.getMenuClockMaterial().name()
                + (menu.isMenuClockGlint() ? "（带附魔光效）" : ""));

        if (menu.isMenuClockRecipeEnabled()) {
            NamespacedKey key = new NamespacedKey(plugin, "menu_clock");
            boolean registered = Bukkit.getRecipe(key) != null;
            if (registered) {
                r.ok("菜单钟合成配方已注册：" + describeIngredients(menu) + " → 菜单钟");
            } else {
                r.fail("菜单钟合成配方未注册（玩家无法合成；检查是否与其他插件配方键冲突）");
            }
        } else {
            r.warn("菜单钟合成配方已按配置关闭（recipe.enabled=false）");
        }

        // 物品构建 + 自识别冒烟
        try {
            ItemStack item = manager.createItem();
            if (manager.isMenuClock(item)) {
                r.ok("菜单钟物品构建与识别正常");
            } else {
                r.fail("菜单钟物品构建后无法被识别（PDC 标记异常）");
            }
        } catch (Throwable t) {
            r.fail("菜单钟物品构建失败：" + t);
        }
    }

    /**
     * 鞘翅兑换配置检查：两种材料都是 0 会让按钮点了只能提示「不可用」，
     * 属于「配错了但没人会发现」的高危静默问题，单独查一遍。
     */
    private static void checkElytraExchange(SfpMain plugin, MenuConfig menu, Report r) {
        int diamond = menu.getElytraDiamondCost();
        int debris = menu.getElytraDebrisCost();
        if (!menu.isElytraExchangeUsable()) {
            r.fail("menu.yml 的 elytra-exchange.diamond-cost 与 debris-cost 都是 0 —— "
                    + "鞘翅兑换按钮点了只会提示「不可用」，请至少把其中一项配成 ≥1");
            return;
        }
        List<String> ways = new ArrayList<>();
        if (diamond > 0) ways.add(diamond + " 钻石");
        if (debris > 0) ways.add(debris + " 下界残骸");
        r.ok("鞘翅兑换：" + String.join(" 或 ", ways)
                + (ways.size() > 1 ? "（优先扣钻石）" : ""));

        // 按钮 lore 是写死的文案，与配置不一致时提醒（否则界面显示和实际扣费对不上）
        MenuConfig.Button button = menu.getButton(menu.getElytraButtonId());
        if (button != null && button.getLore() != null && !button.getLore().isEmpty()) {
            String lore = String.join(" ", button.getLore());
            List<String> stale = new ArrayList<>();
            if (diamond > 0 && !lore.contains(String.valueOf(diamond))) stale.add("钻石数 " + diamond);
            if (debris > 0 && !lore.contains(String.valueOf(debris))) stale.add("残骸数 " + debris);
            if (!stale.isEmpty()) {
                r.warn("按钮 lore 里的数字与 elytra-exchange 配置不一致（" + String.join("、", stale)
                        + "），玩家看到的文案会与实际扣费不符，请同步改 buttons." + button.getId() + ".lore");
            }
        }

        checkElytraPlaceholders(plugin, r);
    }

    /**
     * 兑换文案占位符体检：把 messages.yml 里 menu.elytra-* 文案中出现的占位符
     * 与代码真正会替换的名单比对。
     * <p>
     * 背景：{@code mergeDefaults()} 只补缺失键、不覆盖已存在键，所以线上老配置里的
     * 旧占位符（如 2.10.0 的 {@code {have}}、{@code {cost}}）升级后不会被刷新。
     * 一旦代码不再提供某个占位符，玩家就会看到字面量 {@code {xxx}} —— 编译期与运行期
     * 都发现不了，只有真人点一次按钮才看得到，所以在这里静态查出来。
     */
    private static void checkElytraPlaceholders(SfpMain plugin, Report r) {
        // 代码侧目前会替换的全部占位符（含兼容别名）
        List<String> known = List.of("cost", "cost_text", "cost_need",
                "diamond", "debris", "diamond_have", "debris_have", "have");
        Messages messages = plugin.getConfigManager().messages();
        List<String> problems = new ArrayList<>();
        for (String key : List.of("menu.elytra-success", "menu.elytra-not-enough",
                "menu.elytra-misconfigured")) {
            String text = messages.raw(key, "");
            if (text == null || text.isEmpty()) continue;
            for (String ph : placeholdersIn(text)) {
                if (!known.contains(ph)) problems.add(key + " 里的 {" + ph + "}");
            }
        }
        if (problems.isEmpty()) {
            r.ok("鞘翅兑换文案的占位符都能被替换（不会显示成字面量）");
        } else {
            r.warn("这些占位符在代码里不会被替换，玩家会看到字面量："
                    + String.join("；", problems) + "（请把该文案改成上面支持的占位符）");
        }
    }

    /** 抽出文本里所有 {xxx} 形式的占位符名 */
    private static List<String> placeholdersIn(String text) {
        List<String> result = new ArrayList<>();
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("\\{([a-zA-Z_][a-zA-Z0-9_]*)}").matcher(text);
        while (matcher.find()) {
            result.add(matcher.group(1));
        }
        return result;
    }

    /**
     * 菜单按钮 lore 的「写死数字 / 关键字」漂移体检。
     * <p>
     * lore 是 menu.yml 里的静态文案，不会自动跟随其它配置：例如把 rtp 冷却改成 20 分钟，
     * 按钮上仍然写着「10 分钟」，玩家看到的说明就与实际行为不符。这里把能自动比对的几项查出来，
     * 提示管理员手工同步（鞘翅兑换的数字由 {@link #checkElytraExchange} 单独检查）。
     * <p>
     * 无法自动比对的不查：菜单钟 lore 里的「泥土」是材质的中文名，代码里没有 Material→中文的映射表。
     */
    private static void checkLoreDrift(SfpMain plugin, MenuConfig menu, Report r) {
        List<String> stale = new ArrayList<>();

        // 随机传送按钮：lore 里写死的冷却值
        String rtpLore = buttonLore(menu, menu.getRtpButtonId());
        if (rtpLore != null) {
            int seconds = plugin.getConfigManager().teleport().getRtpCooldownSeconds();
            if (seconds <= 0) {
                stale.add("随机传送按钮 lore 仍写着冷却，但 rtp.cooldown-seconds 已配成 0（实际无冷却）");
            } else if (!rtpLore.contains((seconds / 60) + " 分钟") && !rtpLore.contains(seconds + " 秒")) {
                stale.add("随机传送按钮 lore 的冷却与 rtp.cooldown-seconds（" + seconds + " 秒）不一致");
            }
        }

        if (stale.isEmpty()) {
            r.ok("菜单按钮 lore 与相关配置一致（随机传送冷却）");
        } else {
            r.warn("菜单按钮 lore 与配置不一致，玩家看到的说明会和实际行为不符："
                    + String.join("；", stale) + "（请手工同步 menu.yml 里对应按钮的 lore）");
        }
    }

    /** 取某按钮 lore 的合并文本；按钮不存在或无 lore 时返回 null */
    private static String buttonLore(MenuConfig menu, String buttonId) {
        MenuConfig.Button button = menu.getButton(buttonId);
        if (button == null || button.getLore() == null || button.getLore().isEmpty()) return null;
        return String.join(" ", button.getLore());
    }

    private static String describeIngredients(MenuConfig menu) {
        StringJoiner joiner = new StringJoiner(" + ");
        for (Material material : menu.getMenuClockIngredients()) {
            joiner.add(material.name());
        }
        return joiner.toString();
    }

    /** 把内部动作串翻译成人话 */
    static String describeAction(String action) {
        if (action == null) return "无动作";
        if (action.startsWith(MenuHolder.CMD_PREFIX)) {
            return "执行命令 /" + action.substring(MenuHolder.CMD_PREFIX.length());
        }
        return switch (action) {
            case MenuHolder.ACTION_TRASHBIN -> "打开垃圾桶";
            case MenuHolder.ACTION_HOME -> "打开家列表";
            case MenuHolder.ACTION_WARP -> "打开传送点列表";
            case MenuHolder.ACTION_BACK -> "执行 /back";
            case MenuHolder.ACTION_TPA -> "打开玩家传送目标选择界面";
            case MenuHolder.ACTION_RTP -> "执行 /rtp 随机传送";
            case MenuHolder.ACTION_ELYTRA -> "消耗钻石兑换鞘翅";
            case MenuHolder.ACTION_TOGGLE_UI -> "切换界面样式（dialogUI / 箱子）";
            default -> action;
        };
    }

    // ==================== 数据层 ====================

    private static void checkStorage(SfpMain plugin, Report r) {
        r.section("传送数据层");
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            r.warn("传送系统未启用（teleport.yml 的 enabled 为 false），跳过数据层检查");
            return;
        }
        if (!manager.isStorageAvailable()) {
            r.fail("数据库不可连接 —— /back /home /warp 全部不可用，请看启动日志");
            return;
        }

        Database db = manager.getDatabase();
        r.ok("数据库可连接：" + db.getFile().getName() + "（" + (db.getFile().length() / 1024) + " KB）");

        Connection c = db.getConnection();
        int actual = pragmaInt(c, "PRAGMA user_version");
        int expected = Database.expectedSchemaVersion();
        if (actual == expected) {
            r.ok("表结构版本 v" + actual + "，与代码一致");
        } else if (actual < expected) {
            r.fail("表结构版本 v" + actual + " 低于代码期望的 v" + expected + "，升级没生效（看启动日志里的迁移报错）");
        } else {
            r.warn("表结构版本 v" + actual + " 高于代码期望的 v" + expected
                    + "（可能由更新版本的插件写入；本插件不会改动结构）");
        }

        for (Map.Entry<String, String[]> e : EXPECTED_COLUMNS.entrySet()) {
            List<String> cols = columns(c, e.getKey());
            if (cols.isEmpty()) {
                r.fail("缺少表 " + e.getKey());
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String col : e.getValue()) {
                if (!cols.contains(col)) missing.add(col);
            }
            if (missing.isEmpty()) r.ok("表 " + e.getKey() + " 列齐全（" + cols.size() + " 列）");
            else r.fail("表 " + e.getKey() + " 缺少列：" + String.join(", ", missing));
        }

        String integrity = pragmaText(c, "PRAGMA integrity_check");
        if ("ok".equalsIgnoreCase(integrity)) r.ok("integrity_check 通过");
        else r.fail("integrity_check 未通过：" + integrity);

        String crud = DbDebug.crudSelfTest(plugin);
        if (crud == null) r.ok("增删改查回归通过（家 / 传送点 / 返回点；哨兵数据已清理）");
        else r.fail("增删改查回归失败：" + crud);
    }

    // ==================== 传送内容 ====================

    private static void checkTeleportData(SfpMain plugin, Report r) {
        r.section("传送内容");
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null || !manager.isStorageAvailable()) return;
        Connection c = manager.getDatabase().getConnection();

        List<String> broken = new ArrayList<>();
        collectBrokenWorlds(c, "SELECT home_name, world FROM homes", "家 ", broken, r);
        collectBrokenWorlds(c, "SELECT warp_name, world FROM warps", "传送点 ", broken, r);
        if (broken.isEmpty()) r.ok("所有家 / 传送点指向的世界都存在");
        else r.warn("这些条目指向不存在或未加载的世界（传送时会提示失败）：" + String.join(", ", broken));

        int max = plugin.getConfigManager().teleport().getHomeMaxHomes();
        if (max < 0) {
            r.ok("家数量无上限（max-homes = -1）");
            return;
        }
        List<String> over = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT player_uuid, COUNT(*) AS c FROM homes GROUP BY player_uuid HAVING c > ?")) {
            ps.setInt(1, max);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    over.add(rs.getString("player_uuid") + "(" + rs.getInt("c") + " 个)");
                }
            }
        } catch (SQLException e) {
            r.fail("统计超限玩家失败：" + e.getMessage());
            return;
        }
        if (over.isEmpty()) r.ok("没有玩家的家数量超过上限（" + max + "）");
        else r.warn("这些玩家的家数量已超上限（通常是后来调小了 max-homes）：" + String.join(", ", over));
    }

    private static void collectBrokenWorlds(Connection c, String sql, String label,
                                             List<String> broken, Report r) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                String world = rs.getString("world");
                if (Bukkit.getWorld(world) == null) {
                    broken.add(label + rs.getString(1) + "@" + world);
                }
            }
        } catch (SQLException e) {
            r.fail("查询失败：" + e.getMessage());
        }
    }

    // ==================== 功能模块 ====================

    /**
     * 连锁挖矿配置体检。
     * <p>
     * 重点是「配错了也不报错」那一类：{@code blocks} 里写了不存在的方块名时，
     * 连锁会静默失效（玩家蹲下挖矿毫无反应），而配置本身加载完全正常 ——
     * 只有把名字逐个拿去 {@code Material.valueOf} 比对才能发现。
     */
    private static void checkChain(SfpMain plugin, Report r) {
        r.section("连锁挖矿");
        var cfg = plugin.getConfigManager().chain();
        if (!cfg.isEnabled()) {
            r.warn("连锁挖矿模块未启用（chain.yml 的 enabled 为 false），跳过检查");
            return;
        }
        if (plugin.getChainManager() == null) {
            r.fail("连锁挖矿监听器未注册 —— 检查启动日志与 setupChain()");
            return;
        }

        // 方块名是否都是真实存在的 Material
        List<String> unknown = new ArrayList<>();
        for (String name : cfg.getBlocks()) {
            if (Material.matchMaterial(name) == null) unknown.add(name);
        }
        if (unknown.isEmpty()) {
            r.ok("收录的 " + cfg.getBlocks().size() + " 种连锁方块全部有效");
        } else {
            r.fail("chain.yml 的 blocks 里有 " + unknown.size() + " 个不存在的方块名，连锁会静默失效："
                    + String.join(", ", unknown) + " —— 去掉它们或改成正确的 Material 名");
        }

        if (cfg.getMaxBlocks() < 1) {
            r.fail("max-blocks 小于 1，连锁永远不会触发（应为 1 以上）");
        } else if (cfg.getMaxBlocks() > 256) {
            r.warn("max-blocks 为 " + cfg.getMaxBlocks() + "，一次连锁可能瞬间挖掉几百块，"
                    + "掉落物与区块加载压力都很大，建议控制在 64 以内");
        } else {
            r.ok("单次连锁上限 " + cfg.getMaxBlocks() + " 块");
        }

        if (cfg.getExhaustionPerBlock() <= 0) {
            r.warn("food.exhaustion-per-block 为 " + cfg.getExhaustionPerBlock()
                    + "：连锁不扣饱食度，等于白嫖（若是有意为之可忽略）");
        } else {
            int perTen = (int) Math.floor(10 * cfg.getExhaustionPerBlock() + 0.5);
            r.ok("每块扣 " + cfg.getExhaustionPerBlock() + " 饱食度（挖 10 块约扣 " + perTen + " 点）");
        }

        if (!cfg.isRequireSneak()) {
            r.warn("require.sneak 为 false：不蹲下也会连锁，误伤风险明显上升");
        }
        if (!cfg.isChainBots()) {
            r.ok("假人挖矿不参与连锁（require.chain-bots=false）");
        } else {
            r.warn("require.chain-bots 为 true：假人会一次连锁挖光整片矿脉并扣饱食度");
        }
    }

    /** 处罚系统：数据库连通性 + 三张表结构 + 当前生效处罚数量 */
    private static void checkPunish(SfpMain plugin, Report r) {
        r.section("处罚系统");
        var punishConfig = plugin.getConfigManager().punish();
        if (!punishConfig.isEnabled()) {
            r.warn("处罚系统未启用（punish.yml 的 enabled 为 false），跳过检查");
            return;
        }
        var manager = plugin.getPunishManager();
        if (manager == null) {
            r.fail("处罚管理器未创建 —— 检查启动日志");
            return;
        }
        if (!manager.isStorageAvailable()) {
            r.fail("处罚数据库不可连接 —— 全部处罚命令不可用，请看启动日志");
            return;
        }
        var db = manager.getDatabase();
        r.ok("数据库可连接：" + db.getFile().getAbsolutePath()
                + "（" + (db.getFile().length() / 1024) + " KB）");

        Connection c = db.getConnection();
        int actual = pragmaInt(c, "PRAGMA user_version");
        int expected = cn.starfallplain.sfpmain.punish.PunishDatabase.expectedSchemaVersion();
        if (actual == expected) {
            r.ok("处罚表结构版本 v" + actual + "，与代码一致");
        } else if (actual < expected) {
            r.fail("处罚表结构版本 v" + actual + " 低于代码期望的 v" + expected + "（升级未生效）");
        } else {
            r.warn("处罚表结构版本 v" + actual + " 高于代码期望的 v" + expected + "（本插件不改动结构）");
        }

        for (Map.Entry<String, String[]> e : EXPECTED_PUNISH_COLUMNS.entrySet()) {
            List<String> cols = columns(c, e.getKey());
            if (cols.isEmpty()) {
                r.fail("缺少表 " + e.getKey());
                continue;
            }
            List<String> missing = new ArrayList<>();
            for (String col : e.getValue()) {
                if (!cols.contains(col)) missing.add(col);
            }
            if (missing.isEmpty()) r.ok("表 " + e.getKey() + " 列齐全（" + cols.size() + " 列）");
            else r.fail("表 " + e.getKey() + " 缺少列：" + String.join(", ", missing));
        }

        // 当前生效的封禁 / 禁言数量（看一眼是不是有该清没清的）
        int banned = countRows(c, "SELECT COUNT(*) FROM punishments WHERE type = 'ban'");
        int muted = countRows(c, "SELECT COUNT(*) FROM punishments WHERE type = 'mute'");
        r.ok("当前生效：封禁 " + banned + " 人，禁言 " + muted + " 人");

        // 音效名有效性
        List<String> badSounds = new ArrayList<>();
        checkSound(punishConfig.getSoundPunish(), "sounds.punish", badSounds);
        checkSound(punishConfig.getSoundRevoke(), "sounds.revoke", badSounds);
        if (badSounds.isEmpty()) r.ok("处罚音效名全部有效");
        else r.fail("以下处罚音效名无法解析（会无声）：" + String.join(", ", badSounds));

        // 踢出延迟：配置里写 <=0 时代码会夹到 1，此处提示运维（跨服环境尤其重要）
        int rawKickDelay = punishConfig.raw().getInt("kick-delay-ticks", 1);
        if (rawKickDelay < 1) {
            r.warn("punish.yml 的 kick-delay-ticks = " + rawKickDelay
                    + "，代码已强制按 1 处理；跨服（Velocity/BungeeCord）环境建议保持 ≥ 1，"
                    + "否则被封禁玩家可能卡在「加载地形中」");
        } else {
            r.ok("踢出延迟 kick-delay-ticks = " + rawKickDelay + " tick");
        }
    }

    private static int countRows(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            return -1;
        }
    }

    // ==================== 假人记录 ====================

    /**
     * 假人记录体检：{@code bots.yml} 里的假人会在下次启动时被重建，
     * 若记录指向的世界不存在，重建会一直失败（记录保留，但假人永远不在线，
     * 而且除了翻启动日志没人会发现）。
     */
    private static void checkBots(SfpMain plugin, Report r) {
        r.section("假人记录");
        var cfg = plugin.getConfigManager().bot();
        if (!cfg.isEnabled()) {
            r.warn("假人模块未启用（bot.yml 的 enabled 为 false），跳过检查");
            return;
        }
        if (cfg.getInvalidPickupMaterials().isEmpty()) {
            r.ok("假人拾取白名单物品名有效（空列表表示全部拾取）");
        } else {
            r.fail("假人拾取白名单包含无效物品：" + String.join(", ", cfg.getInvalidPickupMaterials()));
        }
        var manager = plugin.getBotManager();
        if (manager == null) {
            r.fail("假人管理器未创建 —— 检查启动日志");
            return;
        }
        r.ok("已记录 " + manager.size() + " 个假人（当前已加入 " + manager.onlineCount() + " 个）");
        if (!cfg.isRestoreOnStart()) {
            r.warn("restore-on-start 为 false：重启服务器后假人不会自动重建");
        }

        File file = manager.dataFile();
        if (!file.isFile()) {
            r.ok("还没有 bots.yml（没有创建过假人）");
            return;
        }
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        var section = yml.getConfigurationSection("bots");
        if (section == null) return;

        List<String> broken = new ArrayList<>();
        for (String key : section.getKeys(false)) {
            String world = yml.getString("bots." + key + ".world", "");
            if (Bukkit.getWorld(world) == null) broken.add(key + "@" + world);
        }
        if (broken.isEmpty()) {
            r.ok("bots.yml 里所有假人所在的世界都已加载");
        } else {
            r.warn("这些假人记录指向不存在/未加载的世界（下次启动重建会失败，可手工删掉对应条目）："
                    + String.join(", ", broken));
        }
    }

    // ==================== 功能模块 ====================

    private static void checkModules(SfpMain plugin, Report r, CommandSender sender) {
        r.section("功能模块");
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        boolean dominion = Bukkit.getPluginManager().getPlugin("Dominion") != null;
        r.ok("前置依赖：PlaceholderAPI " + (papi ? "已安装" : "未安装")
                + "，Dominion " + (dominion ? "已安装" : "未安装"));
        if (!papi) r.warn("缺少 PlaceholderAPI：%stf_cleantime% 占位符不可用");
        if (!dominion) r.warn("缺少 Dominion：菜单里的领地按钮点击无效果");

        printRuntime(plugin, sender);
    }

    /**
     * 打印各模块开关与实时运行态。{@code /sfp status} 与 {@code /sfp test} 共用，
     * 保证两处看到的信息完全一致。
     */
    static void printRuntime(SfpMain plugin, CommandSender sender) {
        boolean papi = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;
        for (String line : plugin.getConfigManager().describeState(papi)) {
            DbDebug.send(sender, "<dark_gray>" + line.trim() + "</dark_gray>");
        }

        FloorCleanManager clean = plugin.getFloorCleanManager();
        DbDebug.send(sender, "<gray>  · 扫地：</gray>" + (clean == null
                ? "<dark_gray>未启动</dark_gray>"
                : "<white>下次清扫还有 " + clean.formatTime() + "</white>"));

        var sweep = plugin.getSweepExceptionStore();
        if (sweep != null) {
            var exceptions = sweep.getExceptions();
            DbDebug.send(sender, "<gray>  · 扫地管理：</gray>" + (sweep.isAvailable()
                    ? "<white>" + (sweep.isEnabled() ? "已开启" : "已暂停")
                            + "，例外区块 " + (exceptions == null ? "读取失败" : exceptions.size()) + " 个</white>"
                    : "<dark_gray>数据库不可用</dark_gray>"));
        }

        ChainConfig chain = plugin.getConfigManager().chain();
        DbDebug.send(sender, "<gray>  · 连锁挖矿：</gray>" + (chain == null || !chain.isEnabled()
                ? "<dark_gray>未启动</dark_gray>"
                : "<white>上限 " + chain.getMaxBlocks() + " 块 / 每块 "
                        + chain.getExhaustionPerBlock() + " 饱食度</white>"));

        TrashBinManager trash = plugin.getTrashBinManager();
        DbDebug.send(sender, "<gray>  · 垃圾桶：</gray>" + (trash == null
                ? "<dark_gray>未启动</dark_gray>"
                : "<white>" + trash.size() + " 件 / " + trash.getPageCount() + " 页</white>"));

        TeleportManager tp = plugin.getTeleportManager();
        DbDebug.send(sender, "<gray>  · 传送：</gray>" + (tp == null
                ? "<dark_gray>未启动</dark_gray>"
                : "<white>等待中的延迟传送 " + tp.pendingCount() + " 笔</white>"));

        var rtpConfig = plugin.getConfigManager().teleport();
        DbDebug.send(sender, "<gray>  · 随机传送（/rtp）：</gray>"
                + (!rtpConfig.isEnabled() || !rtpConfig.isRtpEnabled()
                        ? "<dark_gray>已关闭（teleport.yml 的 rtp.enabled）</dark_gray>"
                        : "<white>范围 ±" + rtpConfig.getRtpRadius() + "，冷却 "
                                + rtpConfig.getRtpCooldownSeconds() + " 秒，选点上限 "
                                + rtpConfig.getRtpMaxAttempts() + " 次</white>"));

        cn.starfallplain.sfpmain.teleport.TpaManager tpa = plugin.getTpaManager();
        DbDebug.send(sender, "<gray>  · 玩家传送（/tpa）：</gray>" + (tpa == null
                ? "<dark_gray>未启动</dark_gray>"
                : (tpa.isEnabled()
                        ? "<white>待处理请求 " + tpa.pendingCount() + " 笔</white>"
                        : "<dark_gray>已关闭（teleport.yml 的 tpa.enabled）</dark_gray>")));

        cn.starfallplain.sfpmain.bot.BotManager bot = plugin.getBotManager();
        DbDebug.send(sender, "<gray>  · 假人（/sfp player）：</gray>" + (bot == null
                ? "<dark_gray>未启动</dark_gray>"
                : "<white>" + bot.size() + " 个（已加入 " + bot.onlineCount()
                        + "，持续动作 " + bot.activeActionCount() + " 个）</white>"));

        cn.starfallplain.sfpmain.punish.PunishManager punish = plugin.getPunishManager();
        DbDebug.send(sender, "<gray>  · 处罚系统：</gray>" + (punish == null
                ? "<dark_gray>未启动</dark_gray>"
                : (punish.isStorageAvailable()
                        ? "<white>数据库就绪（封禁 " + punish.bannedNames().size()
                                + " 人 / 禁言 " + punish.mutedNames().size() + " 人）</white>"
                        : "<red>数据库不可用</red>")));
    }

    // ==================== 工具 ====================

    private static List<String> columns(Connection c, String table) {
        List<String> cols = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        } catch (SQLException ignored) {
            // 表不存在等情况，返回空列表由调用方报错
        }
        return cols;
    }

    private static int pragmaInt(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static String pragmaText(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            return "(失败：" + e.getMessage() + ")";
        }
    }

    /** 计数并输出的小工具 */
    private static final class Report {
        private final CommandSender sender;
        private int ok;
        private int warn;
        private int fail;

        Report(CommandSender sender) {
            this.sender = sender;
        }

        void section(String name) {
            DbDebug.send(sender, " ");
            DbDebug.send(sender, "<gold>【" + name + "】</gold>");
        }

        void ok(String message) {
            ok++;
            DbDebug.send(sender, "<green>[OK]</green> <gray>" + message + "</gray>");
        }

        void warn(String message) {
            warn++;
            DbDebug.send(sender, "<yellow>[注意]</yellow> <gray>" + message + "</gray>");
        }

        void fail(String message) {
            fail++;
            DbDebug.send(sender, "<red>[错误]</red> <gray>" + message + "</gray>");
        }
    }
}
