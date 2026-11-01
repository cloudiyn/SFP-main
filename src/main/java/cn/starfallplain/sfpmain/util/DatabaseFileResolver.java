package cn.starfallplain.sfpmain.util;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** SQLite 文件路径解析与旧版根目录迁移。 */
public final class DatabaseFileResolver {

    private DatabaseFileResolver() {
    }

    /**
     * 在插件 data/ 子目录解析独立数据文件，并迁移同名旧根目录文件。
     */
    public static File resolveDataFile(JavaPlugin plugin, String fileName) {
        File dataDir = new File(plugin.getDataFolder(), "data");
        if (!dataDir.exists() && !dataDir.mkdirs()) {
            plugin.getLogger().warning("无法创建数据目录：" + dataDir.getAbsolutePath());
        }
        File target = new File(dataDir, fileName);
        migrateLegacy(plugin, new File(plugin.getDataFolder(), fileName), target);
        return target;
    }

    /**
     * 相对文件名放入 data 目录；包含路径分隔符的相对路径保持原有语义.
     * 绝对路径也保持不变，供跨服共享数据库使用。
     */
    public static File resolve(JavaPlugin plugin, String configured, String defaultName) {
        String raw = configured == null ? "" : configured.trim();
        if (raw.isEmpty()) raw = defaultName;

        File configuredFile = new File(raw);
        if (configuredFile.isAbsolute()) {
            ensureParent(plugin, configuredFile);
            return configuredFile;
        }

        boolean simpleName = !raw.contains("/") && !raw.contains("\\");
        File target = simpleName
                ? new File(new File(plugin.getDataFolder(), "data"), raw)
                : new File(plugin.getDataFolder(), raw);
        ensureParent(plugin, target);

        // 旧版本把简单文件名直接放在插件目录，首次启动时迁移到 data/。
        if (simpleName) {
            migrateLegacy(plugin, new File(plugin.getDataFolder(), raw), target);
        }
        return target;
    }

    private static void migrateLegacy(JavaPlugin plugin, File legacy, File target) {
        File legacyWal = new File(legacy.getPath() + "-wal");
        File legacyShm = new File(legacy.getPath() + "-shm");
        File targetWal = new File(target.getPath() + "-wal");
        File targetShm = new File(target.getPath() + "-shm");

        // 两份主库都存在时无法通用判断哪份更新；拒绝静默选择以避免数据丢失。
        if (target.exists() && legacy.exists()) {
            String message = "新旧数据库同时存在，拒绝自动选择以避免数据丢失："
                    + legacy.getAbsolutePath() + " 与 " + target.getAbsolutePath();
            plugin.getLogger().severe(message);
            throw new IllegalStateException(message);
        }
        if (target.exists() || !legacy.exists()) return;
        if (targetWal.exists() || targetShm.exists()) {
            String message = "目标数据库不存在但 data/ 下已有 SQLite 日志文件，拒绝自动迁移："
                    + target.getAbsolutePath();
            plugin.getLogger().severe(message);
            throw new IllegalStateException(message);
        }

        boolean walMoved = false;
        boolean shmMoved = false;
        try {
            // WAL 可能包含尚未 checkpoint 的事务，必须与主库一并迁移。
            if (legacyWal.exists()) { moveFile(legacyWal, targetWal); walMoved = true; }
            if (legacyShm.exists()) { moveFile(legacyShm, targetShm); shmMoved = true; }
            moveFile(legacy, target);
            plugin.getLogger().info("已将数据库从旧位置迁移到 data/：" + legacy.getName());
        } catch (IOException failure) {
            try {
                if (shmMoved && targetShm.exists()) moveFile(targetShm, legacyShm);
                if (walMoved && targetWal.exists()) moveFile(targetWal, legacyWal);
            } catch (IOException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            String message = "旧数据库迁移失败，已停止启动以避免创建空库："
                    + legacy.getAbsolutePath() + " → " + target.getAbsolutePath()
                    + "；原因：" + failure.getMessage();
            plugin.getLogger().severe(message);
            throw new IllegalStateException(message, failure);
        }
    }

    private static void moveFile(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException atomicFailure) {
            Files.move(source.toPath(), target.toPath());
        }
    }

    private static void ensureParent(JavaPlugin plugin, File file) {
        File parent = file.getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            plugin.getLogger().warning("无法创建数据库目录：" + parent.getAbsolutePath());
        }
    }
}
