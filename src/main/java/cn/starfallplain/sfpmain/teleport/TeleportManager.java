package cn.starfallplain.sfpmain.teleport;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.TeleportConfig;
import cn.starfallplain.sfpmain.teleport.db.BackStore;
import cn.starfallplain.sfpmain.teleport.db.Database;
import cn.starfallplain.sfpmain.teleport.db.HomeStore;
import cn.starfallplain.sfpmain.teleport.db.StoredLocation;
import cn.starfallplain.sfpmain.teleport.db.WarpStore;
import cn.starfallplain.sfpmain.util.SoundUtil;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 传送核心管理器。
 * <p>
 * 统一负责：
 * <ul>
 *   <li>SQLite 数据层（{@link Database} + 三个领域 Store）的生命周期</li>
 *   <li>/back 位置记录（死亡、传送前、切换世界、退出）</li>
 *   <li>实际传送（含安全落点、冷却、音效、跨世界判定）</li>
 *   <li>延迟传送（teleport.delay-seconds &gt; 0 时等待并支持移动打断）</li>
 * </ul>
 * 命令与 GUI 都通过本类发起传送，保证行为一致。
 */
public final class TeleportManager {

    /** 延迟传送结束前多少 tick 发起目标区块的异步预加载（20 tick = 1 秒） */
    private static final int PRELOAD_LEAD_TICKS = 20;

    private final SfpMain plugin;
    private final TeleportConfig config;
    private final Database database;
    /** 三张表按领域拆开，共用一个 Database（连接与表结构只有一份） */
    private final HomeStore homeStore;
    private final WarpStore warpStore;
    private final BackStore backStore;

    /** 传送冷却：玩家 UUID → 冷却结束时间（毫秒） */
    private final Map<UUID, Long> teleportCooldowns = new HashMap<>();
    /** 设置家冷却：玩家 UUID → 冷却结束时间（毫秒） */
    private final Map<UUID, Long> setHomeCooldowns = new HashMap<>();
    /** 随机传送冷却：玩家 UUID → 冷却结束时间（毫秒），与普通传送冷却相互独立 */
    private final Map<UUID, Long> rtpCooldowns = new HashMap<>();
    /** 内部传送标记：玩家 UUID → 标记过期时间（毫秒） */
    private final Map<UUID, Long> internalTeleportFlags = new HashMap<>();
    /** 随机传送进行中标记：避免同一玩家并发发起多次（选点较重，需异步） */
    private final java.util.Set<UUID> rtpInProgress = java.util.concurrent.ConcurrentHashMap.newKeySet();
    /** 延迟传送队列：玩家 UUID → 待执行的传送任务 */
    private final Map<UUID, PendingTeleport> pendingTeleports = new HashMap<>();

    /**
     * 一笔待执行的延迟传送。
     *
     * @param destination 目标位置（已完成安全落点解析）
     * @param start       发起传送时的位置（用于判定是否移动）
     * @param task        计划任务（打断时需取消）
     * @param worldName   目标世界名（执行前再校验一次世界是否存在）
     */
    private record PendingTeleport(Location destination, Location start,
                                   BukkitTask task, String worldName) {
    }

    public TeleportManager(SfpMain plugin, TeleportConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.database = new Database(plugin, config.getDbFile(), config.isAutoCommit());
        this.homeStore = new HomeStore(plugin, database);
        this.warpStore = new WarpStore(plugin, database);
        this.backStore = new BackStore(plugin, database);
    }

    public TeleportConfig getConfig() {
        return config;
    }

    public HomeStore getHomeStore() {
        return homeStore;
    }

    public WarpStore getWarpStore() {
        return warpStore;
    }

    public BackStore getBackStore() {
        return backStore;
    }

    /** 数据层本体（供 /sfp db 直接做诊断查询） */
    public Database getDatabase() {
        return database;
    }

    /** 当前等待中的延迟传送数量（供 /sfp status / /sfp test 显示运行态） */
    public int pendingCount() {
        return pendingTeleports.size();
    }

    /** 数据层是否可用（三个 Store 共用同一个连接，判断一次即可） */
    public boolean isStorageAvailable() {
        return database.isAvailable();
    }

    /** 关闭数据库连接（插件卸载时调用） */
    public void shutdown() {
        // 取消所有等待中的延迟传送
        for (PendingTeleport pending : pendingTeleports.values()) {
            if (pending.task() != null) pending.task().cancel();
        }
        pendingTeleports.clear();
        database.close();
    }

    // ==================== 传送冷却 ====================

    /**
     * 检查冷却，未冷却时返回 0；冷却中返回剩余秒数。
     */
    public long getCooldownRemaining(Player player, int cooldownSeconds) {
        if (cooldownSeconds <= 0) return 0;
        Long until = teleportCooldowns.get(player.getUniqueId());
        if (until == null) return 0;
        long remainMs = until - System.currentTimeMillis();
        if (remainMs <= 0) return 0;
        // 向上取整秒
        return (remainMs + 999) / 1000;
    }

    private void markCooldown(Player player, int cooldownSeconds) {
        if (cooldownSeconds <= 0) return;
        teleportCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + cooldownSeconds * 1000L);
    }

    public long getSetHomeCooldownRemaining(Player player) {
        int seconds = config.getHomeSetCooldownSeconds();
        if (seconds <= 0) return 0;
        Long until = setHomeCooldowns.get(player.getUniqueId());
        if (until == null) return 0;
        long remainMs = until - System.currentTimeMillis();
        if (remainMs <= 0) return 0;
        return (remainMs + 999) / 1000;
    }

    public void markSetHomeCooldown(Player player) {
        int seconds = config.getHomeSetCooldownSeconds();
        if (seconds <= 0) return;
        setHomeCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + seconds * 1000L);
    }

    // ==================== 随机传送（/rtp） ====================

    /**
     * 该世界是否允许随机传送（由 teleport.yml 的 {@code rtp.worlds} 白名单决定）。
     * 白名单为空表示所有世界都允许。
     */
    public boolean isRtpWorldAllowed(World world) {
        if (world == null) return false;
        List<String> worlds = config.getRtpWorlds();
        if (worlds == null || worlds.isEmpty()) return true;
        return worlds.contains(world.getName());
    }

    /**
     * 随机传送冷却剩余秒数；未冷却返回 0。
     */
    public long getRtpCooldownRemaining(Player player) {
        int seconds = config.getRtpCooldownSeconds();
        if (seconds <= 0) return 0;
        Long until = rtpCooldowns.get(player.getUniqueId());
        if (until == null) return 0;
        long remainMs = until - System.currentTimeMillis();
        if (remainMs <= 0) return 0;
        return (remainMs + 999) / 1000;
    }

    private void markRtpCooldown(Player player) {
        int seconds = config.getRtpCooldownSeconds();
        if (seconds <= 0) return;
        rtpCooldowns.put(player.getUniqueId(),
                System.currentTimeMillis() + seconds * 1000L);
    }

    /** 该玩家是否正在选点（防止并发重复发起） */
    public boolean isRtpInProgress(Player player) {
        return rtpInProgress.contains(player.getUniqueId());
    }

    /**
     * 发起一次随机传送。选点过程中会异步加载候选区块（避免卡主线程），
     * 找到落点后回到主线程调用统一的 {@link #teleport(Player, StoredLocation)}，
     * 从而复用延迟等待、音效、粒子、受伤打断等完整流程。
     * <p>
     * 冷却与「是否正在进行」由本方法统一判定并给出提示，调用方只负责发起。
     */
    public void randomTeleport(Player player) {
        if (!player.isOnline()) return;
        if (rtpInProgress.contains(player.getUniqueId())) {
            player.sendMessage(plugin.getMessage("rtp.in-progress",
                    "<yellow>正在为你寻找合适的随机传送点，请稍候……</yellow>"));
            return;
        }

        World world = player.getWorld();
        if (!isRtpWorldAllowed(world)) {
            Map<String, String> ph = new HashMap<>();
            ph.put("world", world != null ? world.getName() : "?");
            plugin.getConfigManager().messages().send(player, "rtp.world-not-allowed",
                    "<red>当前世界（{world}）不允许随机传送。</red>", ph);
            return;
        }

        long cd = getRtpCooldownRemaining(player);
        if (cd > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("seconds", String.valueOf(cd));
            ph.put("time", cn.starfallplain.sfpmain.util.TimeUtil.formatTime((int) cd));
            plugin.getConfigManager().messages().send(player, "rtp.cooldown",
                    "<red>随机传送冷却中，请等待 {time}。</red>", ph);
            return;
        }

        rtpInProgress.add(player.getUniqueId());
        plugin.getConfigManager().messages().send(player, "rtp.searching",
                "<yellow>正在为你寻找合适的随机传送点……</yellow>", Map.of());

        final UUID uuid = player.getUniqueId();
        final Location origin = player.getLocation().clone();
        // 在主线程从候选批次开始搜（区块一律走异步加载，不在异步线程碰世界数据）
        tryNextCandidate(world, origin, uuid, 0);
    }

    /**
     * 尝试第 {@code attempt} 个候选点：
     * 先在主线程随机取一个 (x,z)，异步加载该区块，再回主线程判定落点是否安全；
     * 不合格就继续下一个候选，直到 {@code rtp.max-attempts} 用尽。
     * <p>
     * 之所以拆成「取点 → 异步载区块 → 回主线程判定」的一步步链式调用，
     * 是因为 {@code World#getChunkAt} 不能安全地在异步线程调用（Paper 明确禁止），
     * 而异步加载又必须等回调才能读方块。
     */
    private void tryNextCandidate(World world, Location origin, UUID uuid, int attempt) {
        Player player = Bukkit.getPlayer(uuid);
        if (player == null || !player.isOnline()) {
            rtpInProgress.remove(uuid);
            return;
        }
        if (attempt >= config.getRtpMaxAttempts()) {
            // 用尽尝试次数：放弃并清理状态
            rtpInProgress.remove(uuid);
            player.sendMessage(plugin.getMessage("rtp.failed",
                    "<red>没有找到合适的随机传送点，请稍后再试。</red>"));
            return;
        }

        int radius = config.getRtpRadius();
        int minDistance = config.getRtpMinDistance();
        // 用玩家 UUID 混合尝试序号做种子，保证每次点不同但又无需共享 Random 实例
        java.util.Random random = new java.util.Random(
                System.nanoTime() ^ (uuid.getMostSignificantBits() * (attempt + 1L)));
        int x = random.nextInt(radius * 2 + 1) - radius;
        int z = random.nextInt(radius * 2 + 1) - radius;

        if (minDistance > 0) {
            double dx = x - origin.getBlockX();
            double dz = z - origin.getBlockZ();
            if (Math.sqrt(dx * dx + dz * dz) < minDistance) {
                // 太近，直接换下一个（不消耗异步加载）
                tryNextCandidate(world, origin, uuid, attempt + 1);
                return;
            }
        }

        final int fx = x;
        final int fz = z;
        int chunkX = x >> 4;
        int chunkZ = z >> 4;
        world.getChunkAtAsync(chunkX, chunkZ).thenAccept(chunk -> Bukkit.getScheduler().runTask(plugin, () -> {
            // 回到主线程后再读方块并判定
            Player online = Bukkit.getPlayer(uuid);
            if (online == null || !online.isOnline()) {
                rtpInProgress.remove(uuid);
                return;
            }
            Location safe = searchSafeColumn(world, fx, fz);
            if (safe == null) {
                tryNextCandidate(world, origin, uuid, attempt + 1);
                return;
            }
            StoredLocation target = StoredLocation.of(safe);
            if (target == null) {
                tryNextCandidate(world, origin, uuid, attempt + 1);
                return;
            }
            rtpInProgress.remove(uuid);
            if (teleport(online, target)) {
                markRtpCooldown(online);
                Map<String, String> ph = new HashMap<>();
                ph.put("location", target.describe());
                plugin.getConfigManager().messages().send(online, "rtp.success",
                        "<green>已随机传送到（{location}）。</green>", ph);
            } else {
                online.sendMessage(plugin.getMessage("teleport.failed", "<red>传送失败。</red>"));
            }
        }));
    }

    /**
     * 在指定 (x, z) 列上从世界表面向下寻找一处安全落点。
     * <p>
     * 判定标准与 {@link #isSafe(World, int, int, int)} 一致：
     * 脚下是实心非可穿过方块、身位与头部可通行；同时排除水/岩浆/仙人掌/虚空等危险面。
     */
    private Location searchSafeColumn(World world, int x, int z) {
        int top = Math.min(world.getMaxHeight() - 2, world.getHighestBlockYAt(x, z) + 1);
        int bottom = world.getMinHeight() + 1;
        int maxSearch = config.getRtpMaxVerticalSearch();

        int checked = 0;
        for (int y = top; y >= bottom && checked < maxSearch; y--, checked++) {
            if (isSafeSurface(world, x, y, z)) {
                return new Location(world, x + 0.5, y, z + 0.5);
            }
        }
        return null;
    }

    /**
     * 随机传送专用的安全判定：比 {@link #isSafe} 更严格，
     * 额外排除落点在液体/危险方块上的情况（随机点最常见的就是落在水面或岩浆上）。
     */
    private boolean isSafeSurface(World world, int x, int y, int z) {
        Block below = world.getBlockAt(x, y - 1, z);
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);

        // 脚下必须有实心支撑，且不能是危险/液体方块
        if (below.getType() == Material.AIR || below.isPassable()) return false;
        if (below.isLiquid()) return false;
        Material belowType = below.getType();
        if (belowType == Material.MAGMA_BLOCK || belowType == Material.CACTUS
                || belowType == Material.CAMPFIRE || belowType == Material.FIRE
                || belowType == Material.SOUL_FIRE || belowType == Material.POWDER_SNOW
                || belowType == Material.SWEET_BERRY_BUSH || belowType == Material.LAVA) {
            return false;
        }

        // 身位与头部必须可通行且不是液体/危险方块
        if (!feet.isPassable() || feet.isLiquid()) return false;
        if (!head.isPassable() || head.isLiquid()) return false;
        return feet.getType() != Material.POWDER_SNOW && head.getType() != Material.POWDER_SNOW;
    }

    // ==================== 记录位置（/back） ====================

    /** 记录玩家当前位置作为 /back 目标 */
    public void recordLastLocation(Player player) {
        if (!config.isBackEnabled()) return;
        StoredLocation loc = StoredLocation.of(player.getLocation());
        if (loc != null) {
            backStore.save(player.getUniqueId(), loc);
        }
    }

    /** 记录指定位置（用于传送前 / 切换世界等事件） */
    public void recordSpecificLocation(Player player, Location location) {
        if (!config.isBackEnabled()) return;
        StoredLocation loc = StoredLocation.of(location);
        if (loc != null) {
            backStore.save(player.getUniqueId(), loc);
        }
    }

    /**
     * 判断是否为本插件内部发起的传送（避免 /back 自身反复覆盖记录）。
     * 以短时间内是否由 manager 发起为标志。
     */
    public boolean isInternalTeleport(Player player) {
        Long until = internalTeleportFlags.get(player.getUniqueId());
        if (until == null) return false;
        if (until < System.currentTimeMillis()) {
            internalTeleportFlags.remove(player.getUniqueId());
            return false;
        }
        return true;
    }

    /** 标记一次内部传送（短暂窗口内忽略 /back 记录） */
    private void markInternalTeleport(Player player) {
        internalTeleportFlags.put(player.getUniqueId(), System.currentTimeMillis() + 1000L);
    }

    /** 玩家退出时按配置记录 */
    public void recordOnQuit(Player player) {
        if (config.isBackEnabled() && config.isBackRecordQuit()) {
            recordLastLocation(player);
        }
    }

    /**
     * 玩家退出时清理「一次性」运行态：目前只清内部传送标记（1 秒窗口期的临时标志）。
     * <p>
     * <b>刻意不清冷却</b>：传送 / 设置家 / 随机传送的冷却都存的是「冷却结束时间戳」，
     * 玩家退出再进来仍然生效；一旦在这里清掉，就等于给了「重登绕过冷却」的漏洞。
     */
    public void clearTransientState(Player player) {
        internalTeleportFlags.remove(player.getUniqueId());
    }

    // ==================== 传送执行 ====================

    /**
     * 将玩家传送到目标位置（带安全落点、可选延迟与音效/粒子）。
     * <p>
     * 若 teleport.yml 的 {@code teleport.delay-seconds} 大于 0，
     * 则先提示玩家、播放音效、脚下刷粒子，延迟执行；期间移动（跨越方块）、
     * 受到伤害或再次传送都会打断。
     *
     * @param target 目标 StoredLocation
     * @return 传送是否成功发起（延迟模式下为「已开始等待」）
     */
    public boolean teleport(Player player, StoredLocation target) {
        if (target == null) return false;
        if (!target.worldExists()) return false;

        Location dest = target.toLocation();
        if (dest == null) return false;

        if (config.isSafeLocation()) {
            dest = findSafeLocation(dest);
        }

        int delay = config.getDelaySeconds();
        if (delay <= 0) {
            executeTeleport(player, dest);
            return true;
        }
        startDelayedTeleport(player, dest, delay);
        return true;
    }

    /** 立即执行传送（记录 /back、标记内部传送、播放音效） */
    private void executeTeleport(Player player, Location dest) {
        // 传送前记录原位置（用于再次 /back）
        if (config.isBackEnabled() && config.isBackRecordTeleport()) {
            recordLastLocation(player);
        }
        // 标记内部传送，避免监听器再次覆盖 /back 记录
        markInternalTeleport(player);
        player.teleport(dest);
        SoundUtil.play(player, player.getLocation(), config.getTeleportSound(), 0.7f, 1.0f);
    }

    /**
     * 发起一笔延迟传送：提示玩家、播放音效、脚下刷粒子，并逐秒倒计时。
     * 若该玩家已有等待中的传送，先静默取消旧的。
     * <p>
     * 倒计时用 ActionBar 显示剩余时间，精度 0.5 秒（每 10 tick 刷新一次）；
     * 粒子间隔取自 {@code teleport.particle-interval-ticks}，滴答音效固定每秒一次。
     * 距离传送还有 1 秒时异步预加载目标区块，避免落地后卡加载。
     */
    private void startDelayedTeleport(Player player, Location dest, int delaySeconds) {
        cancelPending(player, false);

        Location start = player.getLocation().clone();
        String worldName = dest.getWorld() != null ? dest.getWorld().getName() : null;

        Map<String, String> ph = new HashMap<>();
        ph.put("seconds", String.valueOf(delaySeconds));
        plugin.getConfigManager().messages().send(player, "teleport.delayed",
                "<yellow>将在 {seconds} 秒后传送，移动或受伤将打断。</yellow>", ph);

        // 等待开始音效
        SoundUtil.play(player, player.getLocation(), config.getWaitStartSound(), 0.7f, 1.0f);

        UUID uuid = player.getUniqueId();
        // 以 10 tick（0.5 秒）为步长驱动，ActionBar 显示剩余时间精确到 0.5 秒
        final int totalTicks = delaySeconds * 20;
        final int particleInterval = config.getParticleIntervalTicks();
        final int[] elapsedTicks = {0};
        final int[] sinceParticleTicks = {0};
        final boolean[] preloaded = {false};
        BukkitTask task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            PendingTeleport current = pendingTeleports.get(uuid);
            if (current == null) return; // 已被打断
            if (!player.isOnline()) return;

            // 提前 1 秒异步预加载目标区块（只做一次）。
            // 必须留出提前量：真正传送发生在延迟结束那一刻，若等到最后一刻才发加载请求，
            // 异步加载还没有完成时间，等于完全没有预加载。
            if (!preloaded[0] && totalTicks - elapsedTicks[0] <= PRELOAD_LEAD_TICKS) {
                preloaded[0] = true;
                preloadChunk(current.destination());
            }

            elapsedTicks[0] += 10;
            sinceParticleTicks[0] += 10;
            final double remaining = Math.max(0, (totalTicks - elapsedTicks[0]) / 20.0);

            if (elapsedTicks[0] < totalTicks) {
                // ActionBar 剩余时间（一位小数，如「剩余 2.5 秒」）
                player.sendActionBar(plugin.getConfigManager().messages().component("teleport.actionbar",
                        "<yellow>剩余 <white>{time}</white> 秒</yellow>")
                        .replaceText(b -> b.matchLiteral("{time}")
                                .replacement(formatSeconds(remaining))));
                // 粒子间隔由 teleport.particle-interval-ticks 决定（默认 20 tick = 每秒一次）
                if (sinceParticleTicks[0] >= particleInterval) {
                    sinceParticleTicks[0] = 0;
                    spawnParticles(player);
                }
                // 滴答音效固定每秒一次：与粒子间隔解耦，改间隔不会让音效变得过密/过疏
                if (elapsedTicks[0] % 20 == 0) {
                    SoundUtil.play(player, player.getLocation(), config.getWaitTickSound(), 0.5f, 1.0f);
                }
            }

            if (elapsedTicks[0] >= totalTicks) {
                current.task().cancel();
                pendingTeleports.remove(uuid);
                // 清掉 ActionBar，避免残留倒计时
                player.sendActionBar(net.kyori.adventure.text.Component.empty());
                if (current.worldName() == null || Bukkit.getWorld(current.worldName()) == null) {
                    player.sendMessage(plugin.getMessage("teleport.world-missing",
                            "<red>目标世界不存在或已被卸载。</red>"));
                    return;
                }
                executeTeleport(player, current.destination());
            }
        }, 10L, 10L);

        pendingTeleports.put(uuid, new PendingTeleport(dest, start, task, worldName));
    }

    /** 秒数格式化为「2.5」这种保留一位小数的字符串（整数则省略小数位） */
    private static String formatSeconds(double seconds) {
        if (Math.abs(seconds - Math.rint(seconds)) < 0.001) {
            return String.valueOf((long) Math.rint(seconds));
        }
        return String.format(java.util.Locale.ROOT, "%.1f", seconds);
    }

    /** 在玩家脚下刷传送门粒子（等待期间） */
    private void spawnParticles(Player player) {
        World world = player.getWorld();
        if (world == null) return;
        Location loc = player.getLocation().add(0, 0.5, 0);
        world.spawnParticle(Particle.PORTAL, loc, 25, 0.35, 0.6, 0.35, 0.04);
    }

    /** 异步预加载目标那一个区块（不阻塞主线程，不加 ticket） */
    private void preloadChunk(Location dest) {
        World world = dest.getWorld();
        if (world == null) return;
        int chunkX = dest.getBlockX() >> 4;
        int chunkZ = dest.getBlockZ() >> 4;
        if (!world.isChunkLoaded(chunkX, chunkZ)) {
            world.getChunkAtAsync(chunkX, chunkZ);
        }
    }

    /** 玩家在等待期间受伤 → 取消传送（区别于移动打断的提示） */
    public void cancelOnDamage(Player player) {
        if (!hasPending(player)) return;
        cancelPending(player, false);
        player.sendMessage(plugin.getMessage("teleport.cancelled-damage",
                "<red>传送已取消（你受到了伤害）。</red>"));
    }

    /**
     * 取消等待中的延迟传送。
     *
     * @param notify 是否向玩家发送「已取消」提示
     * @return 是否确实取消了一笔等待中的传送
     */
    public boolean cancelPending(Player player, boolean notify) {
        PendingTeleport pending = pendingTeleports.remove(player.getUniqueId());
        if (pending == null) return false;
        if (pending.task() != null) pending.task().cancel();
        if (player.isOnline()) {
            // 清掉 ActionBar 倒计时，避免打断后残留
            player.sendActionBar(net.kyori.adventure.text.Component.empty());
        }
        if (notify && player.isOnline()) {
            player.sendMessage(plugin.getMessage("teleport.cancelled",
                    "<red>传送已取消（你移动了）。</red>"));
        }
        return true;
    }

    /** 该玩家是否有等待中的延迟传送 */
    public boolean hasPending(Player player) {
        return pendingTeleports.containsKey(player.getUniqueId());
    }

    /**
     * 处理玩家移动事件：若移动跨越了方块坐标，则打断等待中的传送。
     * 只看方块坐标，避免转头 / 微小抖动误打断。
     *
     * @return 是否因此次移动取消了传送
     */
    public boolean handleMove(Player player, Location to) {
        PendingTeleport pending = pendingTeleports.get(player.getUniqueId());
        if (pending == null) return false;
        Location start = pending.start();
        if (start == null) return false;
        boolean movedBlock = start.getBlockX() != to.getBlockX()
                || start.getBlockY() != to.getBlockY()
                || start.getBlockZ() != to.getBlockZ();
        if (movedBlock) {
            cancelPending(player, true);
            return true;
        }
        return false;
    }

    /**
     * 从给定位置出发寻找安全落点：
     * 若脚下或身位是实心方块，则向上/向下搜索合适位置。
     */
    private Location findSafeLocation(Location loc) {
        World world = loc.getWorld();
        if (world == null) return loc;

        int maxDist = config.getSafeSearchDistance();
        int baseX = loc.getBlockX();
        int baseZ = loc.getBlockZ();
        int startY = loc.getBlockY();

        // 先检查原位置是否可用
        if (isSafe(world, baseX, startY, baseZ)) {
            return loc;
        }

        // 从原位置上下交替搜索
        for (int d = 1; d <= maxDist; d++) {
            int upY = startY + d;
            if (world.getMaxHeight() > upY && isSafe(world, baseX, upY, baseZ)) {
                return new Location(world, loc.getX(), upY, loc.getZ(), loc.getYaw(), loc.getPitch());
            }
            int downY = startY - d;
            if (downY > world.getMinHeight() && isSafe(world, baseX, downY, baseZ)) {
                return new Location(world, loc.getX(), downY, loc.getZ(), loc.getYaw(), loc.getPitch());
            }
        }
        // 找不到合适位置就返回原样（交由服务端处理）
        return loc;
    }

    /** 判断某坐标是否适合站立（脚下实心、身位和头部可通行） */
    private boolean isSafe(World world, int x, int y, int z) {
        Block feet = world.getBlockAt(x, y, z);
        Block head = world.getBlockAt(x, y + 1, z);
        Block below = world.getBlockAt(x, y - 1, z);
        // 脚下需要有支撑（非空气且不可穿过）
        if (below.getType() == Material.AIR || below.isPassable()) return false;
        // 身位与头部需要可通行
        return feet.isPassable() && head.isPassable();
    }

    /**
     * 通用传送封装：传送到一个 Bukkit Location（会先转成 StoredLocation）。
     *
     * @return 成功返回 true；世界名缺失或安全位置解析失败返回 false
     */
    public boolean teleportToLocation(Player player, Location loc) {
        StoredLocation stored = StoredLocation.of(loc);
        return teleport(player, stored);
    }

    /**
     * 传送后标记冷却（由命令层在确认传送成功后调用）。
     */
    public void applyTeleportCooldown(Player player, int cooldownSeconds) {
        markCooldown(player, cooldownSeconds);
    }

    /**
     * 传送到指定家（存在性 / 世界 / 冷却校验 + 传送 + 提示）。
     * 供 /home 命令、家列表 GUI、dialogUI 列表共用，保证行为一致。
     */
    public void teleportHome(Player player, String name) {
        StoredLocation target = homeStore.get(player.getUniqueId(), name);
        if (target == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "home.not-found",
                    "<red>不存在名为「{name}」的家。</red>", ph);
            return;
        }
        if (!target.worldExists()) {
            player.sendMessage(plugin.getMessage("teleport.world-missing",
                    "<red>目标世界不存在或已被卸载。</red>"));
            return;
        }
        long cd = getCooldownRemaining(player, config.getHomeTeleportCooldownSeconds());
        if (cd > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("seconds", String.valueOf(cd));
            plugin.getConfigManager().messages().send(player, "teleport.cooldown",
                    "<red>传送冷却中，请等待 {seconds} 秒。</red>", ph);
            return;
        }
        if (teleport(player, target)) {
            applyTeleportCooldown(player, config.getHomeTeleportCooldownSeconds());
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "home.teleport-success",
                    "<green>已传送到家「{name}」。</green>", ph);
        } else {
            player.sendMessage(plugin.getMessage("teleport.failed", "<red>传送失败。</red>"));
        }
    }

    /** 传送到指定公共传送点（与 teleportHome 同一套统一逻辑） */
    public void teleportWarp(Player player, String name) {
        StoredLocation target = warpStore.get(name);
        if (target == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "warp.not-found",
                    "<red>不存在名为「{name}」的传送点。</red>", ph);
            return;
        }
        if (!target.worldExists()) {
            player.sendMessage(plugin.getMessage("teleport.world-missing",
                    "<red>目标世界不存在或已被卸载。</red>"));
            return;
        }
        long cd = getCooldownRemaining(player, config.getWarpTeleportCooldownSeconds());
        if (cd > 0) {
            Map<String, String> ph = new HashMap<>();
            ph.put("seconds", String.valueOf(cd));
            plugin.getConfigManager().messages().send(player, "teleport.cooldown",
                    "<red>传送冷却中，请等待 {seconds} 秒。</red>", ph);
            return;
        }
        if (teleport(player, target)) {
            applyTeleportCooldown(player, config.getWarpTeleportCooldownSeconds());
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "warp.teleport-success",
                    "<green>已传送到「{name}」。</green>", ph);
        } else {
            player.sendMessage(plugin.getMessage("teleport.failed", "<red>传送失败。</red>"));
        }
    }

    /** 删除家（返回是否删掉），供 /delhome、箱子 GUI、dialogUI 共用 */
    public boolean deleteHome(Player player, String name) {
        boolean deleted = homeStore.delete(player.getUniqueId(), name);
        if (!deleted) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "home.not-found",
                    "<red>不存在名为「{name}」的家。</red>", ph);
            return false;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "home.del-success",
                "<green>已删除家「{name}」。</green>", ph);
        return true;
    }

    /** 删除公共传送点（含权限校验），供 /delwarp、箱子 GUI、dialogUI 共用 */
    public boolean deleteWarp(Player player, String name) {
        String permission = config.getWarpDeletePermission();
        if (permission != null && !permission.isBlank() && !player.hasPermission(permission)) {
            player.sendMessage(plugin.getMessage("common.no-permission", "<red>你没有权限使用此命令！</red>"));
            return false;
        }
        boolean deleted = warpStore.delete(name);
        if (!deleted) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(player, "warp.not-found",
                    "<red>不存在名为「{name}」的传送点。</red>", ph);
            return false;
        }
        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        plugin.getConfigManager().messages().send(player, "warp.del-success",
                "<green>已删除传送点「{name}」。</green>", ph);
        return true;
    }
}
