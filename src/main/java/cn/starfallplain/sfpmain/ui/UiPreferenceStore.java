package cn.starfallplain.sfpmain.ui;

import cn.starfallplain.sfpmain.util.DatabaseFileResolver;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

/**
 * 玩家界面偏好存储（{@code data/settings.db} 的 {@code ui_preferences} 表）。旧版根目录文件会自动迁移。
 * <p>
 * 独立于传送数据库（{@code teleport.db}）：界面偏好不属于传送域，且传送模块关闭时
 * 偏好仍要可用，所以这里自建一个极简的连接与建表，不复用传送的 {@code Database}。
 * <p>
 * 单连接 + 同步访问，遵循项目约定只允许在主线程使用；写是低频的（只在玩家切换样式时）。
 */
public final class UiPreferenceStore {

    private final JavaPlugin plugin;
    private Connection connection;

    public UiPreferenceStore(JavaPlugin plugin) {
        this.plugin = plugin;
        try {
            Class.forName("org.sqlite.JDBC");
            File file = DatabaseFileResolver.resolve(plugin, "settings.db", "settings.db");
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
            try (Statement st = connection.createStatement()) {
                st.execute("PRAGMA journal_mode = WAL");
                st.execute("CREATE TABLE IF NOT EXISTS ui_preferences ("
                        + "player_uuid TEXT PRIMARY KEY,"
                        + "ui_mode TEXT NOT NULL DEFAULT 'dialogui')");
                // 传送请求「回应界面」的形式偏好（dialog / tui），与主菜单偏好相互独立
                st.execute("CREATE TABLE IF NOT EXISTS tpa_ui_preferences ("
                        + "player_uuid TEXT PRIMARY KEY,"
                        + "ui_mode TEXT NOT NULL DEFAULT 'dialog')");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("无法打开 settings.db，界面偏好将退回默认值（dialogUI）："
                    + e.getMessage());
            connection = null;
        }
    }

    /** 读取偏好；没有记录或读失败都返回默认 dialogUI */
    public UiMode get(UUID uuid) {
        if (connection == null) return UiMode.DIALOG;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ui_mode FROM ui_preferences WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return UiMode.fromKey(rs.getString("ui_mode"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取界面偏好失败：" + e.getMessage());
        }
        return UiMode.DIALOG;
    }

    /** 保存偏好（覆盖写） */
    public void set(UUID uuid, UiMode mode) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO ui_preferences (player_uuid, ui_mode) VALUES (?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET ui_mode = excluded.ui_mode")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode.key());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("保存界面偏好失败：" + e.getMessage());
        }
    }

    /** 读取「传送回应界面」偏好；没有记录或读失败都返回默认 DIALOG */
    public TpaUiMode getTpaMode(UUID uuid) {
        if (connection == null) return TpaUiMode.DIALOG;
        try (PreparedStatement ps = connection.prepareStatement(
                "SELECT ui_mode FROM tpa_ui_preferences WHERE player_uuid = ?")) {
            ps.setString(1, uuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return TpaUiMode.fromKey(rs.getString("ui_mode"));
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("读取传送界面偏好失败：" + e.getMessage());
        }
        return TpaUiMode.DIALOG;
    }

    /** 保存「传送回应界面」偏好 */
    public void setTpaMode(UUID uuid, TpaUiMode mode) {
        if (connection == null) return;
        try (PreparedStatement ps = connection.prepareStatement(
                "INSERT INTO tpa_ui_preferences (player_uuid, ui_mode) VALUES (?, ?) "
                        + "ON CONFLICT(player_uuid) DO UPDATE SET ui_mode = excluded.ui_mode")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, mode.key());
            ps.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().warning("保存传送界面偏好失败：" + e.getMessage());
        }
    }

    /** 关闭连接（插件卸载时调用） */
    public synchronized void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ignored) {
            // 关闭失败无需处理
        } finally {
            connection = null;
        }
    }
}
