package cn.starfallplain.sfpmain.punish;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.PunishConfig;
import cn.starfallplain.sfpmain.util.DurationParser;
import cn.starfallplain.sfpmain.util.SoundUtil;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 处罚系统核心。
 * <p>
 * 负责处罚 ID 生成、七类动作（警告 / 踢出 / 封禁 / 禁言 / 解封 / 解禁 / 查询）的业务编排，
 * 以及「惰性到期判定」——不轮询、不定时扫描，只在 <b>进服 / 发言 / 查询 / 解禁</b> 这些
 * 必要的时机去检查 {@code expire_at}，过期就地清理并补一条 {@link PunishAction#EXPIRE} 日志。
 * 另在插件启动时清理一次全库过期记录（{@link PunishStore#purgeExpired(long)}）。
 * <p>
 * 本类不做任何权限判断，那是命令层的职责；这里只管「业务上能不能做」。
 */
public final class PunishManager {

    /** 处罚 ID 长度（需求：6 字符） */
    private static final int ID_LENGTH = 6;
    /** 处罚 ID 字符集：字母数字混合（去掉了易混淆的 0/O/1/I/l） */
    private static final char[] ID_ALPHABET =
            "23456789ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz".toCharArray();

    private final SfpMain plugin;
    private final PunishConfig config;
    private final PunishStore store;
    private final SecureRandom random = new SecureRandom();

    /**
     * 生效处罚的内存缓存（UUID → 类型 → 处罚）。
     * <p>
     * 存在的唯一理由是<b>聊天检查在异步线程</b>（{@code AsyncChatEvent}）：缓存让禁言判定变成纯内存操作，
     * 不必在异步线程碰 SQLite 连接。缓存在启动时从库重建，并且在每次施加 / 解除 / 到期时同步更新 ——
     * 与库中的数据保持一致（库仍是唯一真相来源，缓存只是副本）。
     */
    private final Map<UUID, Map<PunishmentType, Punishment>> activeCache = new ConcurrentHashMap<>();

    public PunishManager(SfpMain plugin, PunishConfig config) {
        this.plugin = plugin;
        this.config = config;
        this.store = new PunishStore(plugin,
                new PunishDatabase(plugin, config.getDbFile(), config.isAutoCommit()));
        // 启动时清理一次已过期处罚（之后靠惰性判定收敛）
        int purged = store.purgeExpired(System.currentTimeMillis());
        if (purged > 0) {
            plugin.getLogger().info("处罚系统：启动时清理了 " + purged + " 条已过期处罚。");
        }
        reloadCache();
    }

    /** 从数据库重建生效处罚缓存（启动 / 重载时调用） */
    private void reloadCache() {
        activeCache.clear();
        for (Punishment p : store.listActive()) {
            if (p.isExpired()) {
                // 库里有残留的过期记录，顺手清掉
                store.deleteActive(p.playerUuid(), p.type());
                continue;
            }
            activeCache.computeIfAbsent(p.playerUuid(), k -> new ConcurrentHashMap<>()).put(p.type(), p);
        }
    }

    /** 把一条处罚写进缓存 */
    private void cachePut(Punishment p) {
        activeCache.computeIfAbsent(p.playerUuid(), k -> new ConcurrentHashMap<>()).put(p.type(), p);
    }

    /** 从缓存移除某玩家某类型的处罚 */
    private void cacheRemove(UUID uuid, PunishmentType type) {
        Map<PunishmentType, Punishment> perPlayer = activeCache.get(uuid);
        if (perPlayer == null) return;
        perPlayer.remove(type);
        if (perPlayer.isEmpty()) activeCache.remove(uuid);
    }

    public PunishConfig getConfig() {
        return config;
    }

    public PunishStore getStore() {
        return store;
    }

    public PunishDatabase getDatabase() {
        return store.getDatabase();
    }

    /** 数据库是否可用 */
    public boolean isStorageAvailable() {
        return store.getDatabase().isAvailable();
    }

    // ==================== ID 生成 ====================

    /** 生成一个不与现存处罚冲突的 6 位字母数字 ID */
    private String generateId() {
        String id;
        int guard = 0;
        do {
            StringBuilder sb = new StringBuilder(ID_LENGTH);
            for (int i = 0; i < ID_LENGTH; i++) {
                sb.append(ID_ALPHABET[random.nextInt(ID_ALPHABET.length)]);
            }
            id = sb.toString();
            guard++;
            // 极端情况下（字母表 57 个字符 × 6 位 ≈ 3.4e10 种组合）防死循环
        } while (store.idExists(id) && guard < 1000);
        return id;
    }

    // ==================== 目标解析 ====================

    /**
     * 解析处罚目标：优先在线玩家，其次历史玩家名（players 表），最后按离线玩家名兜底。
     *
     * @return UUID；解析不出来返回 null
     */
    public UUID resolveTarget(String name) {
        if (name == null || name.isBlank()) return null;
        // 1) 在线玩家（按当前名精确 + 忽略大小写）
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return online.getUniqueId();
        // 2) players 表（进服时补录 / 处罚时记录）
        UUID stored = store.findUuidByName(name);
        if (stored != null) return stored;
        // 3) Bukkit 离线缓存兜底
        OfflinePlayer offline = Bukkit.getOfflinePlayerIfCached(name);
        return offline == null ? null : offline.getUniqueId();
    }

    /** 取玩家名（以 players 表 / 在线实例为准，取不到就用传入值） */
    private String resolveName(UUID uuid, String fallback) {
        Player online = Bukkit.getPlayer(uuid);
        if (online != null) return online.getName();
        String stored = store.findNameByName(fallback);
        return stored != null ? stored : fallback;
    }

    // ==================== 惰性到期判定 ====================

    /**
     * 取某玩家当前生效的处罚（查库 + 惰性到期判定）：
     * 已过期的记录会被删除并补一条 {@link PunishAction#EXPIRE} 日志，返回 null。
     * <p>
     * 只能在<b>主线程</b>调用（会访问数据库）。
     */
    public Punishment getActivePunishment(UUID uuid, PunishmentType type) {
        if (uuid == null || type == null) return null;
        Punishment p = store.getActive(uuid, type);
        if (p == null) return null;
        if (p.isExpired()) {
            expire(p);
            return null;
        }
        cachePut(p);
        return p;
    }

    /**
     * 仅从内存缓存取生效处罚，<b>不查库、可安全地在异步线程调用</b>
     * （{@code AsyncChatEvent} 走这里）。
     * <p>
     * 缓存可能与库有极短暂的偏差（例如别的插件改了库、或到期但尚未被主线程发现），
     * 因此这里同样做一次内存级的到期判定：过期即视作无处罚（真正的清理交给主线程的
     * {@link #getActivePunishment} 或进服时的 {@link #activePunishments}）。
     */
    public Punishment getCachedPunishment(UUID uuid, PunishmentType type) {
        if (uuid == null || type == null) return null;
        Map<PunishmentType, Punishment> perPlayer = activeCache.get(uuid);
        if (perPlayer == null) return null;
        Punishment p = perPlayer.get(type);
        if (p == null || p.isExpired()) return null;
        return p;
    }

    /** 处理「到期自动解除」：删生效记录 + 写 EXPIRE 日志 + 清缓存 */
    private void expire(Punishment p) {
        store.deleteActive(p.playerUuid(), p.type());
        cacheRemove(p.playerUuid(), p.type());
        store.insertLog(new PunishLog(0L, p.id(), p.playerUuid(), p.playerName(), p.type(),
                p.reason(), p.operator(), p.createdAt(), p.expireAt(),
                PunishAction.EXPIRE, System.currentTimeMillis()));
        config.log("处罚 " + p.id() + "（" + p.type().displayName() + " " + p.playerName()
                + "）已到期，自动解除。");
    }

    // ==================== 各类处罚动作 ====================

    /**
     * 警告：不写生效表（警告没有「生效中」的概念），只写一条历史日志，
     * 记下本次警告的处罚 ID 便于回溯与 /sfpcheck 查询。
     *
     * @return 处罚 ID
     */
    public String warn(CommandSender operator, UUID target, String targetName, String reason) {
        String id = generateId();
        long now = System.currentTimeMillis();
        store.insertLog(new PunishLog(0L, id, target, targetName, PunishmentType.WARN,
                reason, operatorName(operator), now, Punishment.PERMANENT,
                PunishAction.PUNISH, now));
        config.log("警告 " + id + "：" + targetName + "，原因：" + reason);
        playSound(operator, config.getSoundPunish());
        return id;
    }

    /**
     * 踢出：断开在线玩家连接，并写一条历史日志（同样不写生效表）。
     *
     * @return 处罚 ID；玩家不在线返回 null
     */
    public String kick(CommandSender operator, UUID target, String targetName, String reason) {
        Player online = Bukkit.getPlayer(target);
        if (online == null) return null;
        String id = generateId();
        long now = System.currentTimeMillis();
        store.insertLog(new PunishLog(0L, id, target, targetName, PunishmentType.KICK,
                reason, operatorName(operator), now, Punishment.PERMANENT,
                PunishAction.PUNISH, now));
        config.log("踢出 " + id + "：" + targetName + "，原因：" + reason);
        playSound(operator, config.getSoundPunish());

        Map<String, String> ph = new HashMap<>();
        ph.put("player", targetName);
        ph.put("operator", operatorName(operator) == null ? "控制台" : operatorName(operator));
        ph.put("reason", reason == null ? config.getDefaultReason() : reason);
        ph.put("id", id);
        ph.put("type", PunishmentType.KICK.displayName());
        ph.put("expire", "-");
        ph.put("duration", "-");
        // 同样延后一 tick 再踢：避免代理把「刚连上就被踢」当成连接中断而重投下一个子服，
        // 导致客户端卡在「加载地形中」（详见 PunishListener#onJoin 的说明）。
        var view = config.kickScreen(PunishmentType.KICK, ph);
        plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
            if (online.isOnline()) {
                online.kick(view);
            }
        }, Math.max(1, config.getKickDelayTicks()));
        return id;
    }

    /**
     * 施加封禁。
     *
     * @return 处罚 ID
     */
    public String ban(CommandSender operator, UUID target, String targetName, String reason, long durationMillis) {
        return apply(operator, target, targetName, PunishmentType.BAN, reason, durationMillis);
    }

    /**
     * 施加禁言。
     *
     * @return 处罚 ID
     */
    public String mute(CommandSender operator, UUID target, String targetName, String reason, long durationMillis) {
        return apply(operator, target, targetName, PunishmentType.MUTE, reason, durationMillis);
    }

    /** 施加处罚的公共流程：覆盖旧处罚 + 写生效表 + 写日志 + 按类型执行即时动作（封禁踢人） */
    private String apply(CommandSender operator, UUID target, String targetName,
                         PunishmentType type, String reason, long durationMillis) {
        String id = generateId();
        long now = System.currentTimeMillis();
        long expireAt = durationMillis == DurationParser.PERMANENT
                ? Punishment.PERMANENT : now + durationMillis;

        Punishment punishment = new Punishment(id, target, targetName, type, reason,
                operatorName(operator), now, expireAt);
        store.savePunishment(punishment);
        cachePut(punishment);
        store.insertLog(new PunishLog(0L, id, target, targetName, type, reason,
                operatorName(operator), now, expireAt, PunishAction.PUNISH, now));

        config.log("处罚 " + id + "：" + type.displayName() + " " + targetName
                + "（" + (expireAt == Punishment.PERMANENT ? "永久" : DurationParser.format(durationMillis))
                + "），原因：" + reason);
        playSound(operator, config.getSoundPunish());

        // 封禁把在线玩家踢下线（禁言无需动作，发言时拦截）。
        // 同样延后一 tick：代理环境下在「玩家刚连上、尚未站稳」时立刻踢，会被代理
        // 当作连接中断重投到 try 列表的下一个子服，客户端卡在「加载地形中」。
        // 延后一 tick 能让代理把它当成一次正常踢出，直接断开并显示原因。
        if (type == PunishmentType.BAN) {
            Player online = Bukkit.getPlayer(target);
            if (online != null) {
                var view = buildBanKickMessage(punishment);
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (online.isOnline()) {
                        online.kick(view);
                    }
                }, Math.max(1, config.getKickDelayTicks()));
            }
        }
        return id;
    }

    /**
     * 解除封禁 / 禁言。
     *
     * @return 是否确实解除了（原本没有该处罚则返回 null）
     */
    public Punishment revoke(CommandSender operator, UUID target, PunishmentType type) {
        Punishment active = getActivePunishment(target, type);
        if (active == null) return null;
        store.deleteActive(target, type);
        cacheRemove(target, type);
        long now = System.currentTimeMillis();
        store.insertLog(new PunishLog(0L, active.id(), active.playerUuid(), active.playerName(),
                type, active.reason(), operatorName(operator), active.createdAt(), active.expireAt(),
                type == PunishmentType.BAN ? PunishAction.UNBAN : PunishAction.UNMUTE, now));
        config.log("处罚 " + active.id() + "：" + type.displayName() + " " + active.playerName()
                + " 已由 " + operatorName(operator) + " 手动解除。");
        playSound(operator, config.getSoundRevoke());
        return active;
    }

    // ==================== 查询 ====================

    /**
     * 按处罚 ID 或玩家名查询处罚历史（log 表）。
     * 传入 6 位 ID 时按 ID 精确查；否则按玩家名查该玩家全部历史。
     */
    public List<PunishLog> query(String keyword) {
        if (keyword == null || keyword.isBlank()) return List.of();
        // 先按处罚 ID 查
        List<PunishLog> byId = store.listLogsByPunishmentId(keyword);
        if (!byId.isEmpty()) return byId;
        // 再按玩家名查
        UUID uuid = resolveTarget(keyword);
        if (uuid != null) {
            List<PunishLog> byPlayer = store.listLogsByPlayer(uuid);
            if (!byPlayer.isEmpty()) return byPlayer;
        }
        // players 里没有这个名字时，尝试用处罚记录表里出现过的名字匹配
        return queryLogsByName(keyword);
    }

    /** 按玩家名（忽略大小写）在日志表中模糊匹配 */
    private List<PunishLog> queryLogsByName(String name) {
        List<PunishLog> result = new ArrayList<>();
        for (String stored : store.listAllNames()) {
            if (stored.equalsIgnoreCase(name)) {
                UUID uuid = store.findUuidByName(stored);
                if (uuid != null) result.addAll(store.listLogsByPlayer(uuid));
            }
        }
        return result;
    }

    /** 玩家当前生效的处罚（BAN + MUTE），供进服检查用（主线程，会查库） */
    public List<Punishment> activePunishments(UUID uuid) {
        List<Punishment> result = new ArrayList<>();
        for (Punishment p : store.getActiveAll(uuid)) {
            if (p.isExpired()) {
                expire(p);
            } else {
                cachePut(p);
                result.add(p);
            }
        }
        return result;
    }

    /** 玩家当前生效的封禁；没有返回 null（主线程，会查库） */
    public Punishment activeBan(UUID uuid) {
        return getActivePunishment(uuid, PunishmentType.BAN);
    }

    /** 玩家当前生效的禁言；没有返回 null（主线程，会查库） */
    public Punishment activeMute(UUID uuid) {
        return getActivePunishment(uuid, PunishmentType.MUTE);
    }

    /** 玩家当前生效的禁言（仅内存缓存，异步线程安全，供聊天拦截用） */
    public Punishment cachedMute(UUID uuid) {
        return getCachedPunishment(uuid, PunishmentType.MUTE);
    }

    /** 数据库中已封禁的玩家名（unban 命令补全用） */
    public List<String> bannedNames() {
        return store.listActiveNames(PunishmentType.BAN);
    }

    /** 数据库中已禁言的玩家名（unmute 命令补全用） */
    public List<String> mutedNames() {
        return store.listActiveNames(PunishmentType.MUTE);
    }

    /** 全部历史玩家名（warn/kick/ban/mute 目标补全用） */
    public List<String> knownPlayerNames() {
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
        for (String stored : store.listAllNames()) {
            if (names.stream().noneMatch(n -> n.equalsIgnoreCase(stored))) names.add(stored);
        }
        names.sort(Comparator.comparing(String::toLowerCase, Comparator.naturalOrder()));
        return names;
    }

    // ==================== 进服补录 ====================

    /** 玩家进服时补录名字映射 */
    public void recordJoin(Player player) {
        store.upsertPlayer(player.getUniqueId(), player.getName());
    }

    /** 玩家退出时刷新 last_seen */
    public void recordQuit(Player player) {
        store.upsertPlayer(player.getUniqueId(), player.getName());
    }

    // ==================== 消息构建 ====================

    /**
     * 构建封禁踢下线消息（MiniMessage，占位符 {player} {reason} {operator} {expire} {id}）。
     * 原因支持 MiniMessage（会原样作为标签解析）。
     */
    public Component buildBanKickMessage(Punishment p) {
        return config.kickScreen(PunishmentType.BAN, placeholders(p));
    }

    /** 占位符表：由一条处罚展开 */
    public Map<String, String> placeholders(Punishment p) {
        Map<String, String> map = new HashMap<>();
        map.put("player", p.playerName());
        map.put("operator", p.operator() == null ? "控制台" : p.operator());
        map.put("reason", p.reason() == null ? config.getDefaultReason() : p.reason());
        map.put("id", p.id());
        map.put("type", p.type().displayName());
        // {expire} 为「剩余多久」，{duration} 为「本条处罚总共多长」
        map.put("expire", p.isPermanent()
                ? config.getPermanentText() : DurationParser.format(p.remainingMillis()));
        map.put("duration", p.isPermanent()
                ? config.getPermanentText() : DurationParser.format(p.expireAt() - p.createdAt()));
        return map;
    }

    /** 执行者名字（控制台给 null，展示层再兜底） */
    private static String operatorName(CommandSender sender) {
        return sender instanceof Player player ? player.getName() : null;
    }

    /**
     * 给执行者播放处罚 / 解除音效（音效名取自 punish.yml 的 {@code sounds.punish} / {@code sounds.revoke}）。
     * 控制台执行时没有可播的对象，直接跳过；音效名无效或留空时 {@link SoundUtil} 静默忽略。
     */
    private static void playSound(CommandSender operator, String soundName) {
        if (operator instanceof Player player && player.isOnline()) {
            SoundUtil.play(player, player.getLocation(), soundName, 1.0f, 1.0f);
        }
    }

    // ==================== 关闭 ====================

    public void shutdown() {
        store.getDatabase().close();
    }

    /** 供自检输出：把类型列表格式化 */
    public String describeActive(UUID uuid) {
        List<Punishment> list = activePunishments(uuid);
        if (list.isEmpty()) return "无处罚";
        StringBuilder sb = new StringBuilder();
        for (Punishment p : list) {
            if (sb.length() > 0) sb.append("；");
            sb.append(p.type().displayName()).append(' ')
                    .append(p.isPermanent() ? config.getPermanentText()
                            : "剩余 " + DurationParser.format(p.remainingMillis()));
        }
        return sb.toString();
    }

    /** 统一小写比较，供补全使用 */
    public static boolean matches(String candidate, String input) {
        return candidate.toLowerCase(Locale.ROOT).startsWith(input.toLowerCase(Locale.ROOT));
    }
}
