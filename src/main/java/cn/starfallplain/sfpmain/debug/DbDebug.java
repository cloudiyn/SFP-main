package cn.starfallplain.sfpmain.debug;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.db.Database;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 传送数据库调试（{@code /sfp db ...}）。
 * <p>
 * 全部是**只读诊断**，唯一例外是两个显式的维护动作：{@code check}（PRAGMA 检查）与
 * {@code checkpoint}（把 WAL 合并回主库，便于直接拷贝 .db 文件备份）。
 * <b>刻意不提供「执行任意 SQL」</b> —— 前缀白名单挡不住 {@code SELECT 1; DROP TABLE x}
 * 这类多语句，风险不值当；真需要随便跑 SQL，用外部工具打开 teleport.db 更合适。
 * <p>
 * 输出直接写死在本类里，没有走 messages.yml：诊断条目多且高度动态（含表名/行数/列名），
 * 塞进文案文件既难读也难维护；门槛是 {@code sfpmenu.admin}（仅管理员）。
 */
public final class DbDebug {

    /** 自检用的哨兵 UUID（全 0 除了最低位，不可能是真实玩家） */
    static final java.util.UUID SENTINEL_UUID = new java.util.UUID(0L, 1L);
    static final String SENTINEL_HOME = "__sfp_selftest";
    static final String SENTINEL_WARP = "__sfp_selftest";
    /** 单次输出最多列多少条，超出只提示数量，避免刷屏 */
    private static final int MAX_LINES = 30;

    private DbDebug() {
    }

    // ==================== /sfp db（处罚库） ====================

    /**
     * 处罚数据库概览。供 {@code /sfp db} 在「仅处罚模式」下使用 —— 那时传送库根本没建，
     * 展示传送库只会得到一句「未启用」，对群组服运维没有意义。
     */
    public static void punishOverview(SfpMain plugin, CommandSender sender) {
        var manager = plugin.getPunishManager();
        if (manager == null) {
            send(sender, "<red>处罚系统未启用（punish.yml 的 enabled 为 false）。</red>");
            return;
        }
        if (!manager.isStorageAvailable()) {
            send(sender, "<red>处罚数据库不可用，请查看启动日志。</red>");
            return;
        }
        var db = manager.getDatabase();
        File file = db.getFile();

        send(sender, "<dark_gray>===== 处罚数据库 =====</dark_gray>");
        send(sender, "<gray>文件： <white>" + file.getName() + "</white> <dark_gray>("
                + (file.length() / 1024) + " KB)</dark_gray>");
        send(sender, "<gray>路径： <dark_gray>" + file.getAbsolutePath() + "</dark_gray>");

        Connection c = db.getConnection();
        if (c == null) {
            send(sender, "<red>无法取得数据库连接。</red>");
            return;
        }
        int actual = pragmaInt(c, "PRAGMA user_version");
        int expected = cn.starfallplain.sfpmain.punish.PunishDatabase.expectedSchemaVersion();
        String versionColor = actual == expected ? "green" : "red";
        send(sender, "<gray>表结构版本： <" + versionColor + ">v" + actual + "</" + versionColor
                + "> <gray>（代码期望 v" + expected + "）</gray>");
        send(sender, "<gray>journal 模式： <white>" + pragmaText(c, "PRAGMA journal_mode") + "</white>");

        StringBuilder sb = new StringBuilder("<gray>数据：");
        for (String t : tables(c)) {
            if (t.startsWith("sqlite_")) continue;
            sb.append(" <white>").append(t).append("</white>")
                    .append("<gray> ").append(count(c, t)).append(" 行　");
        }
        send(sender, sb.toString());

        send(sender, "<gray>当前生效： <white>封禁 " + manager.bannedNames().size()
                + "</white> 人，<white>禁言 " + manager.mutedNames().size() + "</white> 人");
    }

    // ==================== /sfp db（传送库） ====================

    /** 概览：文件、版本、journal 模式、各表行数 */
    public static void overview(SfpMain plugin, CommandSender sender) {
        Connection c = connection(plugin, sender);
        if (c == null) return;

        Database database = plugin.getTeleportManager().getDatabase();
        File file = database.getFile();

        send(sender, "<dark_gray>===== 传送数据库 =====</dark_gray>");
        send(sender, "<gray>文件： <white>" + file.getName() + "</white> <dark_gray>("
                + (file.length() / 1024) + " KB)</dark_gray>");
        send(sender, "<gray>路径： <dark_gray>" + file.getAbsolutePath() + "</dark_gray>");

        int actual = pragmaInt(c, "PRAGMA user_version");
        int expected = Database.expectedSchemaVersion();
        String versionColor = actual == expected ? "green" : "red";
        send(sender, "<gray>表结构版本： <" + versionColor + ">v" + actual + "</" + versionColor
                + "> <gray>（代码期望 v" + expected + "）</gray>");

        send(sender, "<gray>journal 模式： <white>" + pragmaText(c, "PRAGMA journal_mode")
                + "</white>　<gray>WAL 文件： <white>"
                + (new File(file.getPath() + "-wal").exists() ? "存在" : "无") + "</white>");
        send(sender, "<gray>外键约束： <white>"
                + (pragmaInt(c, "PRAGMA foreign_keys") == 1 ? "已开启" : "已关闭") + "</white>");

        // 表与行数
        List<String> tables = tables(c);
        StringBuilder sb = new StringBuilder("<gray>数据：");
        for (String t : tables) {
            if (t.startsWith("sqlite_")) continue;
            sb.append(" <white>").append(t).append("</white>")
                    .append("<gray> ").append(count(c, t)).append(" 行　");
        }
        send(sender, sb.toString());

        send(sender, "<gray>子命令： <white>tables</white> / <white>homes</white> / "
                + "<white>warps</white> / <white>back</white> / <white>check</white> / "
                + "<white>checkpoint</white>");
    }

    /** 表清单：每张表的列名 */
    public static void tables(SfpMain plugin, CommandSender sender) {
        Connection c = connection(plugin, sender);
        if (c == null) return;
        send(sender, "<dark_gray>===== 表结构 =====</dark_gray>");
        for (String t : tables(c)) {
            if (t.startsWith("sqlite_")) continue;
            send(sender, "<aqua>" + t + "</aqua> <dark_gray>(" + count(c, t) + " 行)</dark_gray> "
                    + "<gray>" + columns(c, t) + "</gray>");
        }
    }

    /** 某个玩家的家（默认执行者自己） */
    public static void homes(SfpMain plugin, CommandSender sender, String playerArg) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            send(sender, "<red>传送系统未启用。</red>");
            return;
        }
        java.util.UUID uuid = resolveUuid(sender, playerArg);
        if (uuid == null) return;

        List<String> names = manager.getHomeStore().listNames(uuid);
        send(sender, "<dark_gray>===== 家（" + uuid + "） " + names.size() + " 个 =====</dark_gray>");
        if (names.isEmpty()) {
            send(sender, "<gray>（无）</gray>");
            return;
        }
        for (String name : limit(names)) {
            StoredLocation loc = manager.getHomeStore().get(uuid, name);
            send(sender, describeEntry(name, loc));
        }
        if (names.size() > MAX_LINES) {
            send(sender, "<dark_gray>…还有 " + (names.size() - MAX_LINES) + " 个未显示</dark_gray>");
        }
    }

    /** 全部公共传送点 */
    public static void warps(SfpMain plugin, CommandSender sender) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            send(sender, "<red>传送系统未启用。</red>");
            return;
        }
        List<String> names = manager.getWarpStore().listNames();
        send(sender, "<dark_gray>===== 传送点 共 " + names.size() + " 个 =====</dark_gray>");
        if (names.isEmpty()) {
            send(sender, "<gray>（无）</gray>");
            return;
        }
        for (String name : limit(names)) {
            send(sender, describeEntry(name, manager.getWarpStore().get(name)));
        }
        if (names.size() > MAX_LINES) {
            send(sender, "<dark_gray>…还有 " + (names.size() - MAX_LINES) + " 个未显示</dark_gray>");
        }
    }

    /** 某个玩家的 /back 记录（默认执行者自己） */
    public static void back(SfpMain plugin, CommandSender sender, String playerArg) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            send(sender, "<red>传送系统未启用。</red>");
            return;
        }
        java.util.UUID uuid = resolveUuid(sender, playerArg);
        if (uuid == null) return;
        send(sender, "<dark_gray>===== /back 记录（" + uuid + "）=====</dark_gray>");
        send(sender, describeEntry("last", manager.getBackStore().get(uuid)));
    }

    /** PRAGMA 完整性检查 */
    public static void check(SfpMain plugin, CommandSender sender) {
        Connection c = connection(plugin, sender);
        if (c == null) return;
        String integrity = pragmaText(c, "PRAGMA integrity_check");
        send(sender, "<gray>integrity_check： "
                + ("ok".equalsIgnoreCase(integrity) ? "<green>ok</green>"
                : "<red>" + integrity + "</red>"));
        String fk = pragmaText(c, "PRAGMA foreign_key_check");
        send(sender, "<gray>foreign_key_check： "
                + (fk == null || fk.isBlank() ? "<green>无问题</green>" : "<yellow>" + fk + "</yellow>"));
    }

    /** 把 WAL 合并回主库（便于直接拷贝 .db 文件） */
    public static void checkpoint(SfpMain plugin, CommandSender sender) {
        Connection c = connection(plugin, sender);
        if (c == null) return;
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA wal_checkpoint(TRUNCATE)")) {
            // 返回一行三列：busy / log 帧数 / checkpoint 后写入主库的帧数
            if (rs.next()) {
                send(sender, "<green>WAL 已合并</green> <gray>busy=" + rs.getInt(1)
                        + " log=" + rs.getInt(2) + " checkpointed=" + rs.getInt(3) + "</gray>");
            }
        } catch (SQLException e) {
            send(sender, "<red>checkpoint 失败：" + e.getMessage() + "</red>");
        }
    }

    // ==================== 自检：增删改查回归 ====================

    /**
     * 对三个 Store 各做一遍「写 → 读 → 比对 → 清理」的回归，返回 null 表示全部通过。
     * <p>
     * 用固定的哨兵 UUID + 哨兵名称，不碰真实玩家数据；无论成功失败都会在 finally 里清理。
     * {@code last_locations} 没有删除接口（业务上不需要），这里用一条 DELETE 收尾。
     */
    static String crudSelfTest(SfpMain plugin) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) return "传送系统未启用";
        if (!manager.isStorageAvailable()) return "数据库不可连接";

        StoredLocation probe = new StoredLocation("__sfp_selftest_world", 1.5, 64.0, -2.5, 90f, 0f);
        List<String> problems = new ArrayList<>();

        // 家
        try {
            if (!manager.getHomeStore().save(SENTINEL_UUID, SENTINEL_HOME, probe)) {
                problems.add("home.save 返回 false");
            } else {
                StoredLocation got = manager.getHomeStore().get(SENTINEL_UUID, SENTINEL_HOME);
                if (!probe.equals(got)) problems.add("home.get 读回不一致：" + got);
                if (!manager.getHomeStore().listNames(SENTINEL_UUID).contains(SENTINEL_HOME)) {
                    problems.add("home.listNames 未包含写入项");
                }
                if (manager.getHomeStore().count(SENTINEL_UUID) < 1) {
                    problems.add("home.count 为 0");
                }
                if (!manager.getHomeStore().delete(SENTINEL_UUID, SENTINEL_HOME)) {
                    problems.add("home.delete 返回 false");
                } else if (manager.getHomeStore().get(SENTINEL_UUID, SENTINEL_HOME) != null) {
                    problems.add("home.delete 后仍能读到");
                }
            }
        } catch (Throwable t) {
            problems.add("home 回归异常：" + t);
        } finally {
            manager.getHomeStore().delete(SENTINEL_UUID, SENTINEL_HOME);
        }

        // 传送点
        try {
            if (!manager.getWarpStore().save(SENTINEL_WARP, probe)) {
                problems.add("warp.save 返回 false");
            } else {
                StoredLocation got = manager.getWarpStore().get(SENTINEL_WARP);
                if (!probe.equals(got)) problems.add("warp.get 读回不一致：" + got);
                if (!manager.getWarpStore().listNames().contains(SENTINEL_WARP)) {
                    problems.add("warp.listNames 未包含写入项");
                }
                if (!manager.getWarpStore().delete(SENTINEL_WARP)) {
                    problems.add("warp.delete 返回 false");
                } else if (manager.getWarpStore().get(SENTINEL_WARP) != null) {
                    problems.add("warp.delete 后仍能读到");
                }
            }
        } catch (Throwable t) {
            problems.add("warp 回归异常：" + t);
        } finally {
            manager.getWarpStore().delete(SENTINEL_WARP);
        }

        // 返回点（BackStore 无删除接口，用 SQL 收尾）
        try {
            if (!manager.getBackStore().save(SENTINEL_UUID, probe)) {
                problems.add("back.save 返回 false");
            } else {
                StoredLocation got = manager.getBackStore().get(SENTINEL_UUID);
                if (!probe.equals(got)) problems.add("back.get 读回不一致：" + got);
            }
        } catch (Throwable t) {
            problems.add("back 回归异常：" + t);
        } finally {
            try (PreparedStatement ps = manager.getDatabase().getConnection()
                    .prepareStatement("DELETE FROM last_locations WHERE player_uuid = ?")) {
                ps.setString(1, SENTINEL_UUID.toString());
                ps.executeUpdate();
                manager.getDatabase().commit();
            } catch (SQLException ignored) {
                // 清理失败不影响自检结论
            }
        }

        return problems.isEmpty() ? null : String.join("；", problems);
    }

    // ==================== 工具 ====================

    /** 取数据层连接；拿不到时给发送者提示并返回 null */
    private static Connection connection(SfpMain plugin, CommandSender sender) {
        TeleportManager manager = plugin.getTeleportManager();
        if (manager == null) {
            send(sender, "<red>传送系统未启用（teleport.yml 的 enabled 为 false）。</red>");
            return null;
        }
        if (!manager.isStorageAvailable()) {
            send(sender, "<red>数据库不可用，请查看启动日志中「传送数据库」相关告警。</red>");
            return null;
        }
        return manager.getDatabase().getConnection();
    }

    /** 解析玩家名 → UUID：先查在线玩家，再查离线缓存（不走网络） */
    private static java.util.UUID resolveUuid(CommandSender sender, String playerArg) {
        if (playerArg == null || playerArg.isBlank()) {
            if (sender instanceof Player player) return player.getUniqueId();
            send(sender, "<red>控制台执行时请指定玩家名：/sfp db homes|back <玩家名></red>");
            return null;
        }
        Player online = Bukkit.getPlayerExact(playerArg);
        if (online != null) return online.getUniqueId();
        OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(playerArg);
        if (cached == null) {
            send(sender, "<red>找不到玩家「" + playerArg + "」（不在线且无离线缓存）。</red>");
            return null;
        }
        return cached.getUniqueId();
    }

    /** 一条位置记录的可读形式，世界不存在时标红 */
    private static String describeEntry(String name, StoredLocation loc) {
        if (loc == null) {
            return "<gray> · </gray><white>" + name + "</white> <dark_gray>（无记录）</dark_gray>";
        }
        boolean ok = loc.worldExists();
        return "<gray> · </gray><white>" + name + "</white> <dark_gray>" + loc.describe()
                + "</dark_gray> " + (ok ? "<green>世界正常</green>" : "<red>世界不存在</red>");
    }

    private static List<String> limit(List<String> list) {
        return list.size() <= MAX_LINES ? list : list.subList(0, MAX_LINES);
    }

    private static List<String> tables(Connection c) {
        List<String> result = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")) {
            while (rs.next()) result.add(rs.getString(1));
        } catch (SQLException e) {
            // 忽略：后续行数显示为 -1
        }
        return result;
    }

    private static String columns(Connection c, String table) {
        List<String> cols = new ArrayList<>();
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) cols.add(rs.getString("name"));
        } catch (SQLException e) {
            return "(读取失败)";
        }
        return "[" + String.join(", ", cols) + "]";
    }

    private static int count(Connection c, String table) {
        try (Statement st = c.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    private static int pragmaInt(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getInt(1) : -1;
        } catch (SQLException e) {
            return -1;
        }
    }

    /** 取 PRAGMA 结果的首列首行；无结果返回空串 */
    private static String pragmaText(Connection c, String sql) {
        try (Statement st = c.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            return "(失败：" + e.getMessage() + ")";
        }
    }

    static void send(CommandSender sender, String miniMessage) {
        sender.sendMessage(Messages.deserialize(miniMessage));
    }
}
