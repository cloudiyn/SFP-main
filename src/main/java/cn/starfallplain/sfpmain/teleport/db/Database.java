package cn.starfallplain.sfpmain.teleport.db;

import cn.starfallplain.sfpmain.util.DatabaseFileResolver;
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
 * SQLite 连接与表结构管理。
 * <p>
 * 数据库文件位于插件数据目录的 data/ 子目录（默认 data/teleport.db）；旧版根目录文件会自动迁移。
 * 采用单连接 + 同步访问（传送数据量小、写入频率低，无需连接池）。
 * <p>
 * <b>单连接只允许在主线程访问</b>：{@link #getConnection()} 本身是同步的，但返回的连接
 * 由调用方在锁外使用；当前所有数据库调用都发生在主线程（命令、事件、GUI、同步调度任务），
 * 若以后改成异步保存，需要给数据访问层补同步。
 *
 * <h2>表结构</h2>
 * <ul>
 *   <li>{@code homes} —— 个人家：玩家 UUID + 家名 + 位置</li>
 *   <li>{@code warps} —— 公共传送点：传送点名 + 位置</li>
 *   <li>{@code last_locations} —— /back 记录：玩家 UUID + 最后位置</li>
 * </ul>
 *
 * <h2>以后要改表结构，按这三步写</h2>
 * <ol>
 *   <li>把 {@link #SCHEMA_VERSION} 加一；</li>
 *   <li>在 {@link #migrate(int)} 里补一段 {@code if (from < N) { ... }}；</li>
 *   <li>具体动作：
 *     <ul>
 *       <li>加<b>新表</b> —— {@code execute("CREATE TABLE IF NOT EXISTS ...")}</li>
 *       <li>加<b>新列</b> —— {@code addColumnIfMissing("homes", "note", "TEXT")}</li>
 *       <li>加<b>索引</b> —— {@code execute("CREATE INDEX IF NOT EXISTS ...")}</li>
 *     </ul>
 *   </li>
 * </ol>
 * 每一步都必须能重复执行而不报错（{@code IF NOT EXISTS}；{@link #addColumnIfMissing} 内部
 * 会先查 {@code PRAGMA table_info}），这样中途失败后重跑也不会把库弄坏。
 * <p>
 * <b>SQLite 的 ALTER TABLE 有限制</b>：只能加列、改列名、删列（3.35+），
 * <b>不能</b>加「无默认值的 NOT NULL 列」，也不能改列类型或主键 —— 这种情况需要
 * 建新表 → 搬数据 → 删旧表 → 改名，不能在 {@link #migrate(int)} 里一行搞定。
 */
public final class Database {

    /**
     * 当前代码期望的表结构版本，存在 SQLite 的 {@code PRAGMA user_version} 中。
     * <p>
     * 已上线老库的 user_version 也是 0，全新库同样是 0，因此从 0 开始逐级判断能同时覆盖两种
     * 情况：新库会把所有步骤依次跑一遍（建表语句本身幂等），老库只补它缺的那几级。
     * <p>
     * <b>每次改动表结构都必须 +1，否则老库不会升级。</b>
     */
    private static final int SCHEMA_VERSION = 1;

    // ==================== 表结构定义（v1）====================

    private static final String DDL_HOMES = """
            CREATE TABLE IF NOT EXISTS homes (
                player_uuid TEXT NOT NULL,
                home_name   TEXT NOT NULL,
                world       TEXT NOT NULL,
                x           REAL NOT NULL,
                y           REAL NOT NULL,
                z           REAL NOT NULL,
                yaw         REAL NOT NULL DEFAULT 0,
                pitch       REAL NOT NULL DEFAULT 0,
                created_at  INTEGER NOT NULL,
                PRIMARY KEY (player_uuid, home_name)
            )""";

    private static final String DDL_WARPS = """
            CREATE TABLE IF NOT EXISTS warps (
                warp_name  TEXT PRIMARY KEY,
                world      TEXT NOT NULL,
                x          REAL NOT NULL,
                y          REAL NOT NULL,
                z          REAL NOT NULL,
                yaw        REAL NOT NULL DEFAULT 0,
                pitch      REAL NOT NULL DEFAULT 0,
                created_at INTEGER NOT NULL
            )""";

    private static final String DDL_LAST_LOCATIONS = """
            CREATE TABLE IF NOT EXISTS last_locations (
                player_uuid TEXT PRIMARY KEY,
                world       TEXT NOT NULL,
                x           REAL NOT NULL,
                y           REAL NOT NULL,
                z           REAL NOT NULL,
                yaw         REAL NOT NULL DEFAULT 0,
                pitch       REAL NOT NULL DEFAULT 0,
                updated_at  INTEGER NOT NULL
            )""";

    private final JavaPlugin plugin;
    private final File dbFile;
    private final boolean autoCommit;

    private Connection connection;

    public Database(JavaPlugin plugin, String fileName, boolean autoCommit) {
        this.plugin = plugin;
        this.autoCommit = autoCommit;
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            plugin.getLogger().warning("无法创建插件数据目录，传送数据库可能无法保存。");
        }
        this.dbFile = DatabaseFileResolver.resolve(plugin, fileName, "teleport.db");
        connect();
        initSchema();
    }

    /** 建立连接并加载驱动 */
    private void connect() {
        try {
            // 显式加载 SQLite 驱动（Paper 插件类加载器下更稳妥）
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            plugin.getLogger().warning("未找到 SQLite JDBC 驱动，传送功能将不可用：" + e.getMessage());
        }
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + dbFile.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA foreign_keys = ON");
                // WAL 提升并发读写表现（单文件场景安全）
                st.execute("PRAGMA journal_mode = WAL");
            }
            connection.setAutoCommit(autoCommit);
        } catch (SQLException e) {
            plugin.getLogger().severe("无法连接传送数据库：" + e.getMessage());
            connection = null;
        }
    }

    // ==================== 表结构初始化与迁移 ====================

    /**
     * 确保数据库表结构达到 {@link #SCHEMA_VERSION}。
     * <p>
     * 老库（user_version 比当前低）会自动升级；比代码新的库只告警、不做任何改动，
     * 避免旧版插件把新版结构改坏。
     */
    private void initSchema() {
        if (connection == null) return;

        // 只用于日志措辞：进迁移前 homes 还不存在，说明是全新库
        boolean fresh = !tableExists("homes");
        int from = readUserVersion();

        if (from > SCHEMA_VERSION) {
            plugin.getLogger().warning("传送数据库表结构版本为 v" + from
                    + "，高于本插件支持的 v" + SCHEMA_VERSION
                    + "（可能由更新版本的插件写入），本次不做任何结构变更。");
            return;
        }

        try {
            migrate(from);
            writeUserVersion(SCHEMA_VERSION);
            commit();

            if (fresh) {
                plugin.getLogger().info("传送数据库已初始化（表结构版本 v" + SCHEMA_VERSION + "）。");
            } else if (from != SCHEMA_VERSION) {
                plugin.getLogger().info("传送数据库表结构已从 v" + from
                        + " 升级到 v" + SCHEMA_VERSION + "。");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("升级传送数据库表结构失败（v" + from + " → v" + SCHEMA_VERSION
                    + "）：" + e.getMessage());
        }
    }

    /**
     * 逐级升级表结构。
     * <p>
     * 以后新增结构就在这里追加 {@code if (from < N)} 分支；每一步都要能重复执行，
     * 且不要删除历史步骤（老库需要按顺序补上来）。
     */
    private void migrate(int from) throws SQLException {
        if (from < 1) {
            // v1：初始三张表
            execute(DDL_HOMES, DDL_WARPS, DDL_LAST_LOCATIONS);
        }

        // ---- 以后新增结构往下写，例如 ----
        // if (from < 2) {
        //     addColumnIfMissing("homes", "note", "TEXT");
        //     addColumnIfMissing("warps", "category", "TEXT NOT NULL DEFAULT '默认'");
        //     execute("CREATE INDEX IF NOT EXISTS idx_warps_category ON warps(category)");
        // }
    }

    /** 数据库文件（供 /sfp db 显示路径与大小） */
    public File getFile() {
        return dbFile;
    }

    /** 代码期望的表结构版本（供 /sfp db 与实际版本对比） */
    public static int expectedSchemaVersion() {
        return SCHEMA_VERSION;
    }

    // ==================== 结构工具（供 migrate 使用）====================

    /** 依次执行若干条 SQL；任一失败即抛出，后续步骤不再执行（下次启动会从当前版本重试） */
    private void execute(String... statements) throws SQLException {
        try (Statement st = connection.createStatement()) {
            for (String sql : statements) {
                st.execute(sql);
            }
        }
    }

    /** 表是否已存在 */
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

    /** 表的列名集合（{@code PRAGMA table_info}） */
    private List<String> columnsOf(String table) {
        List<String> columns = new ArrayList<>();
        // 表名只来自本类内部常量，不来自外部输入；PRAGMA 不支持占位符，故直接拼接
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA table_info(" + table + ")")) {
            while (rs.next()) {
                columns.add(rs.getString("name"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取表结构失败（" + table + "）：" + e.getMessage());
        }
        return columns;
    }

    /**
     * 幂等地给表加一列：列已存在则什么都不做。
     *
     * @param definition 列定义，例如 {@code "TEXT"}、{@code "TEXT NOT NULL DEFAULT ''"}
     *                   —— SQLite 不允许新增「无默认值的 NOT NULL 列」
     */
    private void addColumnIfMissing(String table, String column, String definition) throws SQLException {
        if (columnsOf(table).contains(column)) return;
        execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
        plugin.getLogger().info("传送数据库：" + table + " 表已新增列 " + column + "。");
    }

    /** 读取 {@code PRAGMA user_version}；读不到按 0 处理（等价于老库 / 全新库） */
    private int readUserVersion() {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("PRAGMA user_version")) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (SQLException e) {
            plugin.getLogger().warning("读取传送数据库版本失败，按 0 处理：" + e.getMessage());
            return 0;
        }
    }

    /** 写入 {@code PRAGMA user_version} */
    private void writeUserVersion(int version) throws SQLException {
        execute("PRAGMA user_version = " + version);
    }

    // ==================== 连接管理 ====================

    /** 获取连接，若已断开则尝试重连 */
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

    /** 是否可用 */
    public boolean isAvailable() {
        return getConnection() != null;
    }

    /** 手动提交（auto-commit 关闭时使用） */
    public synchronized void commit() {
        if (connection == null || autoCommit) return;
        try {
            connection.commit();
        } catch (SQLException e) {
            plugin.getLogger().warning("提交传送数据失败：" + e.getMessage());
        }
    }

    /** 关闭连接（插件卸载时调用） */
    public synchronized void close() {
        if (connection == null) return;
        try {
            if (!autoCommit && !connection.isClosed()) {
                connection.commit();
            }
            connection.close();
        } catch (SQLException e) {
            plugin.getLogger().warning("关闭传送数据库失败：" + e.getMessage());
        } finally {
            connection = null;
        }
    }
}
