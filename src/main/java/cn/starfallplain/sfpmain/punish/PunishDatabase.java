package cn.starfallplain.sfpmain.punish;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

/**
 * 处罚系统的 SQLite 连接与表结构管理（独立数据库文件 punish.db）。
 * <p>
 * 与传送数据库（{@code teleport.db}）完全隔离：独立文件、独立连接，
 * 这样处罚数据的读写不会影响传送，删库也互不牵连。
 * 仍然沿用「单连接 + 主线程同步访问」的简单模型（处罚操作频率很低）。
 *
 * <h2>表结构</h2>
 * <ul>
 *   <li>{@code players} —— UUID ↔ 最近玩家名映射（进服时补录，用于离线目标选择）</li>
 *   <li>{@code punishments} —— 当前生效的处罚（BAN / MUTE），一人一类型最多一条</li>
 *   <li>{@code punishment_logs} —— 处罚历史（含解除动作），只增不删</li>
 * </ul>
 *
 * <h2>以后要改表结构，按这三步写</h2>
 * <ol>
 *   <li>把 {@link #SCHEMA_VERSION} 加一；</li>
 *   <li>在 {@link #migrate(int)} 里补一段 {@code if (from < N) { ... }}；</li>
 *   <li>加新表用 {@code CREATE TABLE IF NOT EXISTS}，加列用 {@link #addColumnIfMissing}，
 *       加索引用 {@code CREATE INDEX IF NOT EXISTS}。</li>
 * </ol>
 */
public final class PunishDatabase {

    /** 当前代码期望的表结构版本（存 PRAGMA user_version）。改结构必须 +1 */
    private static final int SCHEMA_VERSION = 1;

    // ==================== 表结构定义（v1）====================

    /** 玩家名映射：处罚离线玩家、按名补全时用 */
    private static final String DDL_PLAYERS = """
            CREATE TABLE IF NOT EXISTS players (
                uuid      TEXT PRIMARY KEY,
                name      TEXT NOT NULL,
                last_seen INTEGER NOT NULL
            )""";

    /** 当前生效处罚；expire_at 为 -1 表示永久 */
    private static final String DDL_PUNISHMENTS = """
            CREATE TABLE IF NOT EXISTS punishments (
                punishment_id TEXT PRIMARY KEY,
                player_uuid   TEXT NOT NULL,
                player_name   TEXT NOT NULL,
                type          TEXT NOT NULL,
                reason        TEXT,
                operator      TEXT,
                created_at    INTEGER NOT NULL,
                expire_at     INTEGER NOT NULL
            )""";

    /** 处罚历史记录（含每次解除动作），只增不删 */
    private static final String DDL_LOGS = """
            CREATE TABLE IF NOT EXISTS punishment_logs (
                log_id        INTEGER PRIMARY KEY AUTOINCREMENT,
                punishment_id TEXT NOT NULL,
                player_uuid   TEXT NOT NULL,
                player_name   TEXT NOT NULL,
                type          TEXT NOT NULL,
                reason        TEXT,
                operator      TEXT,
                created_at    INTEGER NOT NULL,
                expire_at     INTEGER NOT NULL,
                action        TEXT NOT NULL,
                action_at     INTEGER NOT NULL
            )""";

    /** 索引：按玩家 + 类型快速查当前处罚；按名字查历史 */
    private static final String[] DDL_INDEXES = {
            "CREATE INDEX IF NOT EXISTS idx_punish_player ON punishments(player_uuid, type)",
            "CREATE INDEX IF NOT EXISTS idx_punish_name ON punishments(player_name)",
            "CREATE INDEX IF NOT EXISTS idx_logs_player ON punishment_logs(player_uuid)",
            "CREATE INDEX IF NOT EXISTS idx_logs_pid ON punishment_logs(punishment_id)",
            "CREATE INDEX IF NOT EXISTS idx_players_name ON players(name)"
    };

    private final JavaPlugin plugin;
    private final File dbFile;
    private final boolean autoCommit;

    private Connection connection;

    public PunishDatabase(JavaPlugin plugin, String fileName, boolean autoCommit) {
        this.plugin = plugin;
        this.autoCommit = autoCommit;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，处罚数据库可能无法保存。");
        }
        this.dbFile = resolveDbFile(plugin, fileName);
        connect();
        initSchema();
    }

    /**
     * 解析数据库文件位置，支持三种写法：
     * <ul>
     *   <li><b>相对文件名</b>（如 {@code punish.db}）—— 放在插件数据目录
     *       {@code plugins/SFP-main/} 下，与旧行为一致；</li>
     *   <li><b>相对路径</b>（如 {@code data/punish.db}）—— 同样相对插件数据目录；</li>
     *   <li><b>绝对路径</b>（如 {@code /opt/mc/shared/punish.db} 或 Windows 的
     *       {@code D:/mc/shared/punish.db}）—— 直接使用，可用于接入现成的封禁数据库，
     *       或把数据放到插件目录之外（如挂载盘、跨服共享目录）。</li>
     * </ul>
     * 绝对路径的父目录若不存在会自动创建，避免 SQLite 打开时报
     * {@code path to database does not exist}。
     */
    private static File resolveDbFile(JavaPlugin plugin, String configured) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) raw = "punish.db";
        File file = new File(raw);
        if (!file.isAbsolute()) {
            file = new File(plugin.getDataFolder(), raw);
        }
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建处罚数据库目录：" + parent.getAbsolutePath()
                    + "，请检查路径是否存在或权限是否足够。");
        }
        return file;
    }


    /** 建立连接并加载驱动 */
    private void connect() {
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("未找到 SQLite JDBC 驱动，处罚功能将不可用：" + e.getMessage());
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                st.execute("PRAGMA journal_mode = WAL");
            }
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            plugin.getLogger().severe("无法连接处罚数据库：" + e.getMessage());
            connection = null;
        }
    }

    // ==================== 表结构初始化与迁移 ====================

    private void initSchema() {
        if (connection == null) return;

        boolean fresh = !tableExists("punishments");
        int from = readUserVersion();

        if (from > SCHEMA_VERSION) {
            plugin.getLogger().warning("处罚数据库表结构版本为 v" + from
                    + "，高于本插件支持的 v" + SCHEMA_VERSION + "，本次不做任何结构变更。");
            return;
        }

        try {
            migrate(from);
            writeUserVersion(SCHEMA_VERSION);
            commit();

            if (fresh) {
                plugin.getLogger().info("处罚数据库已初始化（表结构版本 v" + SCHEMA_VERSION + "）。");
            } else if (from != SCHEMA_VERSION) {
                plugin.getLogger().info("处罚数据库表结构已从 v" + from
                        + " 升级到 v" + SCHEMA_VERSION + "。");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("升级处罚数据库表结构失败（v" + from + " → v" + SCHEMA_VERSION
                    + "）：" + e.getMessage());
        }
    }

    /** 逐级升级；以后新增结构在后面追加 {@code if (from < N)} 分支 */
    private void migrate(int from) throws SQLException {
        if (from < 1) {
            execute(DDL_PLAYERS, DDL_PUNISHMENTS, DDL_LOGS);
            execute(DDL_INDEXES);
        }
    }

    // ==================== 结构工具 ====================

    private void execute(String... statements) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    private boolean tableExists(String table) {
        String sql = "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?";
        try (var ps = connection.prepareStatement(sql)) {
            ps.setString(1, table);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private List<String> columnsOf(String table) {
        List<String> columns = new ArrayList<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取处罚表结构失败（" + table + "）：" + e.getMessage());
        }
        return columns;
    }

    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (columnsOf(table).contains(column)) return;
        execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        plugin.getLogger().info("处罚数据库：" + table + " 表已新增列 " + column + "。");
    }

    private int readUserVersion() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("读取处罚数据库版本失败，按 0 处理：" + e.getMessage());
            return 0;
        }
    }

    private void writeUserVersion(int version) throws SQLException {
        execute("PRAGMA user_version = " + version);
    }

    // ==================== 连接管理 ====================

    public synchronized Connection getConnection() {
        try {
            if (connection == null || connection.isClosed()) {
                connect();
            }
        } catch (SQLException e) {
            connect();
        }
        return connection;
    }

    public boolean isAvailable() {
        return getConnection() != null;
    }

    public File getFile() {
        return dbFile;
    }

    public static int expectedSchemaVersion() {
        return SCHEMA_VERSION;
    }

    public synchronized void commit() {
        if (connection == null || autoCommit) return;
        try {
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("提交处罚数据失败：" + e.getMessage());
        }
    }

    public synchronized void close() {
        if (connection == null) return;
        try {
            if (!autoCommit && !connection.isClosed()) {
                connection.commit();
            }
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("关闭处罚数据库失败：" + e.getMessage());
        } finally {
            connection = null;
        }
    }
}
