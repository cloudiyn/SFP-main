package cn.starfallplain.sfpmain.clean;

import cn.starfallplain.sfpmain.util.DatabaseFileResolver;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

/** 自动扫地开关与例外区块的 SQLite 存储。 */
public final class SweepExceptionStore implements AutoCloseable {
    private final JavaPlugin plugin;
    private final File file;
    private Connection connection;
    private boolean enabled = true;
    private Set<String> exceptionCache = new HashSet<>();
    private boolean exceptionsLoaded;

    public SweepExceptionStore(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = DatabaseFileResolver.resolveDataFile(plugin, "sweep.db");
        try {
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS sweep_settings (setting_key TEXT PRIMARY KEY, setting_value TEXT NOT NULL)");
                statement.executeUpdate("CREATE TABLE IF NOT EXISTS sweep_exceptions (world TEXT NOT NULL, chunk_x INTEGER NOT NULL, chunk_z INTEGER NOT NULL, PRIMARY KEY(world, chunk_x, chunk_z))");
            }
            try (PreparedStatement query = connection.prepareStatement("SELECT setting_value FROM sweep_settings WHERE setting_key = 'enabled'");
                 ResultSet rs = query.executeQuery()) {
                if (rs.next()) enabled = Boolean.parseBoolean(rs.getString(1));
                else setEnabled(true);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("扫地管理数据库初始化失败：" + e.getMessage());
            close();
        }
    }

    public synchronized boolean isAvailable() { return connection != null; }
    public synchronized boolean isEnabled() { return enabled; }
    public File getFile() { return file; }

    public synchronized boolean setEnabled(boolean value) {
        if (connection == null) return false;
        try (PreparedStatement ps = connection.prepareStatement("INSERT INTO sweep_settings(setting_key, setting_value) VALUES('enabled', ?) ON CONFLICT(setting_key) DO UPDATE SET setting_value = excluded.setting_value")) {
            ps.setString(1, Boolean.toString(value));
            ps.executeUpdate();
            enabled = value;
            return true;
        } catch (SQLException e) { plugin.getLogger().warning("保存扫地开关失败：" + e.getMessage()); return false; }
    }

    /** @return true=状态已改变，false=本来就是这样，null=数据库写入失败（与「未改变」区分，避免骗人提示） */
    public synchronized Boolean setException(String world, int x, int z, boolean add) {
        if (connection == null) return null;
        String sql = add ? "INSERT OR IGNORE INTO sweep_exceptions(world, chunk_x, chunk_z) VALUES(?, ?, ?)" : "DELETE FROM sweep_exceptions WHERE world = ? AND chunk_x = ? AND chunk_z = ?";
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, world); ps.setInt(2, x); ps.setInt(3, z);
            boolean changed = ps.executeUpdate() > 0;
            if (changed) {
                if (add) exceptionCache.add(key(world, x, z));
                else exceptionCache.remove(key(world, x, z));
            }
            return changed;
        } catch (SQLException e) { plugin.getLogger().warning("保存扫地区块例外失败：" + e.getMessage()); return null; }
    }

    public synchronized Set<String> getExceptions() {
        if (connection == null) return null;
        if (exceptionsLoaded) return new HashSet<>(exceptionCache);
        Set<String> loaded = new HashSet<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery("SELECT world, chunk_x, chunk_z FROM sweep_exceptions")) {
            while (rs.next()) loaded.add(key(rs.getString(1), rs.getInt(2), rs.getInt(3)));
            exceptionCache = loaded;
            exceptionsLoaded = true;
            return new HashSet<>(exceptionCache);
        } catch (SQLException e) {
            plugin.getLogger().warning("读取扫地区块例外失败，本轮跳过清扫：" + e.getMessage());
            return null;
        }
    }

    public static String key(String world, int x, int z) { return world + ":" + x + ":" + z; }

    @Override public synchronized void close() {
        if (connection != null) try { connection.close(); } catch (SQLException e) { plugin.getLogger().warning("关闭扫地数据库失败：" + e.getMessage()); }
        connection = null;
    }
}