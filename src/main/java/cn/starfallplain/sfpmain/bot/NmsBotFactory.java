package cn.starfallplain.sfpmain.bot;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.BoundingBox;
import org.bukkit.util.NumberConversions;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假人的 NMS 反射工具箱（Paper 26.3，Mojang 映射）。
 * <p>
 * 假人是真玩家实体（{@code ServerPlayer}），Bukkit 没有创建 / 操控玩家的公开 API，
 * 所以全部走反射。类名与方法签名均以服务端 jar 实地核对过（见 features.md「假人」一节）。
 * <p>
 * 两类入口：
 * <ul>
 *   <li><b>构造假人</b>：{@link #spawn} —— Carpet 假人那条路（ServerPlayer + 假连接 +
 *       {@code PlayerList#placeNewPlayer}）</li>
 *   <li><b>操控假人</b>：动作类方法（攻击 / 挖掘 / 使用 / 移动 / 睡觉 / 骑乘 / 复活……），
 *       多数通过「构造服务端数据包 → 直接调用 {@code ServerGamePacketListenerImpl} 的
 *       public handle 方法」实现，与真客户端发包走完全相同的服务端逻辑</li>
 * </ul>
 * 所有方法失败都返回 {@code false} / 空，并打一次警告（同一失败不重复刷屏），
 * 不影响服务器运行。
 */
final class NmsBotFactory {

    // ==================== NMS 类名（26.3 实测） ====================
    private static final String NMS_SERVER = "net.minecraft.server.MinecraftServer";
    private static final String NMS_LEVEL = "net.minecraft.server.level.ServerLevel";
    private static final String NMS_PLAYER = "net.minecraft.server.level.ServerPlayer";
    private static final String NMS_ENTITY = "net.minecraft.world.entity.Entity";
    private static final String NMS_ITEMSTACK = "net.minecraft.world.item.ItemStack";
    private static final String NMS_HAND = "net.minecraft.world.InteractionHand";
    private static final String NMS_BLOCKPOS = "net.minecraft.core.BlockPos";
    private static final String NMS_DIRECTION = "net.minecraft.core.Direction";
    private static final String NMS_VEC3 = "net.minecraft.world.phys.Vec3";
    private static final String NMS_BLOCK_HIT = "net.minecraft.world.phys.BlockHitResult";
    private static final String CLIENT_INFO = "net.minecraft.server.level.ClientInformation";
    private static final String PACKET_FLOW = "net.minecraft.network.protocol.PacketFlow";
    private static final String CONNECTION = "net.minecraft.network.Connection";
    private static final String LISTENER = "net.minecraft.server.network.ServerGamePacketListenerImpl";
    private static final String COOKIE = "net.minecraft.server.network.CommonListenerCookie";
    private static final String GAME_PROFILE = "com.mojang.authlib.GameProfile";
    private static final String REMOVAL_REASON = "net.minecraft.world.entity.Entity$RemovalReason";

    /** 反射缓存：方法（含"查不到"的负缓存，避免每次反射全类扫描） */
    private static final Map<String, Method> METHODS = new ConcurrentHashMap<>();
    /** 反射缓存：字段 */
    private static final Map<String, Field> FIELDS = new ConcurrentHashMap<>();
    /** 已经警告过的失败点（同一处只警告一次） */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Method MISSING = marker();
    /** 字段「查不到」的哨兵（ConcurrentHashMap 不允许 null 值） */
    private static final Field MISSING_FIELD = markerField();

    private NmsBotFactory() {
    }

    /** 负缓存占位方法（永远调不到） */
    private static Method marker() {
        try {
            return Object.class.getDeclaredMethod("toString");
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 负缓存占位字段（永远读不到） */
    private static Field markerField() {
        try {
            // Boolean 里有一个 private final int value 字段，借它当占位
            return Boolean.class.getDeclaredField("value");
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(e);
        }
    }

    // ==================== 反射工具 ====================

    private static Class<?> clazz(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    private static Method method(Class<?> owner, String name, Class<?>... params) {
        if (owner == null) return null;
        String key = owner.getName() + "#" + name + Arrays.toString(params);
        Method cached = METHODS.get(key);
        if (cached != null) return cached == MISSING ? null : cached;
        Method found = null;
        try {
            found = owner.getMethod(name, params);
        } catch (Throwable ignored) {
            // 签名不存在（版本漂移），负缓存
        }
        METHODS.put(key, found == null ? MISSING : found);
        return found;
    }

    private static Field field(Class<?> owner, String name) {
        if (owner == null) return null;
        String key = owner.getName() + "#field:" + name;
        Field cached = FIELDS.get(key);
        if (cached != null) return cached == MISSING_FIELD ? null : cached;
        Field found = null;
        // 先找 public 字段；找不到再沿继承链找声明字段（如 FishingHook#biting 是 private）
        for (Class<?> c = owner; c != null && c != Object.class && found == null; c = c.getSuperclass()) {
            try {
                found = c.getField(name);
            } catch (Throwable ignored) {
                // 不是 public，继续试声明字段
            }
            if (found == null) {
                try {
                    found = c.getDeclaredField(name);
                    found.setAccessible(true);
                } catch (Throwable ignored) {
                    // 继续往父类找
                }
            }
        }
        // ConcurrentHashMap 不接受 null 值：用哨兵标记「查不到」，否则 put(key, null) 会抛 NPE
        FIELDS.put(key, found == null ? MISSING_FIELD : found);
        return found;
    }

    /** 同一失败点只警告一次，避免动作任务每 tick 刷屏 */
    private static void warnOnce(JavaPlugin plugin, String point, Throwable t) {
        if (WARNED.add(point)) {
            plugin.getLogger().warning("假人 NMS 调用失败（" + point + "）：" + rootCause(t));
        }
    }

    private static void warnOnce(JavaPlugin plugin, String point, String detail) {
        if (WARNED.add(point)) {
            plugin.getLogger().warning("假人 NMS 调用失败（" + point + "）：" + detail);
        }
    }

    private static Object invoke(Object target, Method m, Object... args) throws Exception {
        return m.invoke(target, args);
    }

    private static String rootCause(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) cur = cur.getCause();
        return cur.getClass().getName() + ": " + cur.getMessage();
    }

    // ==================== 基础对象获取 ====================

    /** 取 NMS 实体对象（假人是 ServerPlayer，其余如坐骑 / 鱼漂也要用） */
    static Object nms(JavaPlugin plugin, org.bukkit.entity.Entity entity) {
        try {
            Method getHandle = method(entity.getClass(), "getHandle");
            return getHandle == null ? null : getHandle.invoke(entity);
        } catch (Throwable t) {
            warnOnce(plugin, "getHandle", t);
            return null;
        }
    }

    /** 取假人的数据包监听器（ServerGamePacketListenerImpl） */
    private static Object listener(JavaPlugin plugin, Player bot, Object nmsPlayer) {
        try {
            Field f = field(nmsPlayer.getClass(), "connection");
            return f == null ? null : f.get(nmsPlayer);
        } catch (Throwable t) {
            warnOnce(plugin, "connection", t);
            return null;
        }
    }

    /** 取假人的游戏模式（ServerPlayerGameMode） */
    private static Object gameMode(JavaPlugin plugin, Player bot, Object nmsPlayer) {
        try {
            Field f = field(nmsPlayer.getClass(), "gameMode");
            return f == null ? null : f.get(nmsPlayer);
        } catch (Throwable t) {
            warnOnce(plugin, "gameMode", t);
            return null;
        }
    }

    /** 取服务端 PlayerList（Paper 26.3 的 CraftServer#getHandle() 直接返回它） */
    private static Object playerList(JavaPlugin plugin, Player bot) {
        try {
            Object craftServer = Bukkit.getServer();
            Method getHandle = method(craftServer.getClass(), "getHandle");
            return getHandle == null ? null : getHandle.invoke(craftServer);
        } catch (Throwable t) {
            warnOnce(plugin, "playerList", t);
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, int paramCount) {
        for (Method m : type.getMethods()) {
            if (m.getName().equals(name) && m.getParameterCount() == paramCount) return m;
        }
        return null;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> enumClass, String constant) {
        return Enum.valueOf((Class<Enum>) enumClass, constant);
    }

    // ==================== 创建 / 移除假人 ====================

    /**
     * 在指定位置创建一个假人。
     *
     * @param skinSource 皮肤来源玩家；null 表示用默认皮肤
     * @return 创建出来的在线玩家对象（服务端已把它当作真玩家）
     * @throws Exception 任一步骤失败（NMS 签名变化、世界未加载等）
     */
    static Player spawn(JavaPlugin plugin, Player skinSource, World world, Location loc,
                        String name, UUID uuid) throws Exception {
        Object craftServer = Bukkit.getServer();
        Object nmsServer = invoke(craftServer, method(craftServer.getClass(), "getServer"));
        Object playerList = invoke(craftServer, method(craftServer.getClass(), "getHandle"));
        Object nmsLevel = invoke(world, method(world.getClass(), "getHandle"));

        // 1) GameProfile（可附带皮肤的签名属性）
        Class<?> profileClass = clazz(GAME_PROFILE);
        Object profile = profileClass.getConstructor(UUID.class, String.class).newInstance(uuid, name);
        if (skinSource != null) {
            copyTextures(plugin, skinSource, profile, profileClass);
        }

        // 2) ClientInformation.createDefault()
        Class<?> clientInfoClass = clazz(CLIENT_INFO);
        Object clientInfo = clientInfoClass.getMethod("createDefault").invoke(null);

        // 3) ServerPlayer(server, level, profile, clientInfo)
        Class<?> nmsServerClass = clazz(NMS_SERVER);
        Class<?> nmsLevelClass = clazz(NMS_LEVEL);
        Class<?> nmsPlayerClass = clazz(NMS_PLAYER);
        Object serverPlayer = nmsPlayerClass
                .getConstructor(nmsServerClass, nmsLevelClass, profileClass, clientInfoClass)
                .newInstance(nmsServer, nmsLevel, profile, clientInfo);

        // 位置：实体加入世界前先摆到目标坐标（方法名在映射里很稳定，仍做兜底）
        try {
            nmsPlayerClass.getMethod("setPos", double.class, double.class, double.class)
                    .invoke(serverPlayer, loc.getX(), loc.getY(), loc.getZ());
        } catch (NoSuchMethodException e) {
            plugin.getLogger().warning("未找到 setPos(double,double,double)，假人 " + name
                    + " 可能出现在默认位置。");
        }

        // 4) 本地假连接 + 数据包监听器
        Class<?> packetFlowClass = clazz(PACKET_FLOW);
        Object serverbound = enumValue(packetFlowClass, "SERVERBOUND");
        Class<?> connectionClass = clazz(CONNECTION);
        Object connection = connectionClass.getConstructor(packetFlowClass).newInstance(serverbound);

        // 关键一步：给假连接装上 netty channel。
        // placeNewPlayer 会向这个连接写登录相关的数据包，Connection.channel 为 null 时会在
        // channel.writeAndFlush(...) 处 NPE（真机验证时就是这么失败的）。
        // EmbeddedChannel 是 netty 自带的测试用 channel：它是「已连接」状态，写入只会进
        // 自己的 outbound 队列而不会真的发网络 —— 正好当作一个吞包的黑洞。
        // 队列会由 BotManager 定期清空，避免长期运行堆积。
        Class<?> embeddedChannelClass = Class.forName("io.netty.channel.embedded.EmbeddedChannel");
        Object channel = embeddedChannelClass.getConstructor().newInstance();
        // Connection.channel 是 public 字段
        connectionClass.getField("channel").set(connection, channel);

        Class<?> cookieClass = clazz(COOKIE);
        Object cookie = cookieClass.getMethod("createInitial", profileClass, boolean.class)
                .invoke(null, profile, false);

        Class<?> listenerClass = clazz(LISTENER);
        Object listener = listenerClass
                .getConstructor(nmsServerClass, connectionClass, nmsPlayerClass, cookieClass)
                .newInstance(nmsServer, connection, serverPlayer, cookie);
        // connection 字段是 public 的
        Field connectionField = nmsPlayerClass.getField("connection");
        connectionField.set(serverPlayer, listener);

        // 5) 走服务端自己的加入流程；它会同时把玩家计入在线列表并加载周围区块
        try {
            playerList.getClass().getMethod("placeNewPlayer", connectionClass, nmsPlayerClass, cookieClass)
                    .invoke(playerList, connection, serverPlayer, cookie);
        } catch (Throwable t) {
            // 兜底：只把它塞进世界（能加载区块与 tick，但可能不计入在线玩家列表）
            plugin.getLogger().warning("假人 " + name + " 走 placeNewPlayer 失败，退回 addNewPlayer："
                    + rootCause(t));
            nmsLevelClass.getMethod("addNewPlayer", nmsPlayerClass).invoke(nmsLevel, serverPlayer);
        }

        Player bot = Bukkit.getPlayer(uuid);
        if (bot == null) {
            throw new IllegalStateException("假人已加入服务端但取不到 Bukkit 玩家对象（uuid=" + uuid + "）");
        }
        return bot;
    }

    /**
     * 把假人从服务端摘掉。
     * <p>
     * <b>不能用 Bukkit 的 {@code kick()}</b>：它依赖向客户端发送断开包，而假人没有真正的
     * 客户端（连接是一条被吞包的空管道），实测调用后假人依旧留在在线列表里。
     * 因此直接调用服务端的 {@code PlayerList#remove(ServerPlayer)}，它会走完移除流程：
     * 从在线列表 / UUID 索引 / 名字索引里摘掉，并广播退出消息、保存玩家数据。
     * <p>
     * 注意：Paper 26.3 的 {@code CraftServer#getHandle()} 返回的就是 PlayerList。
     */
    static void remove(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return;
        Object playerList = playerList(plugin, bot);
        if (playerList == null) return;
        try {
            Method remove = null;
            Method fallback = null;
            for (Method m : playerList.getClass().getMethods()) {
                if (!m.getName().equals("remove") || m.getParameterCount() != 1) continue;
                Class<?> param = m.getParameterTypes()[0];
                // 参数必须能接收假人实体（否则是别的 remove 重载）
                if (!param.isInstance(nmsPlayer)) continue;
                // 精准命中 ServerPlayer 优先；其余「能接收」的重载只作兜底 ——
                // getMethods() 的顺序没有保证，光靠「名字 + 参数个数」挑容易挑错方法。
                if (param.getSimpleName().equals("ServerPlayer")) {
                    remove = m;
                    break;
                }
                if (fallback == null) fallback = m;
            }
            if (remove == null) remove = fallback;
            if (remove == null) throw new NoSuchMethodException("PlayerList#remove(ServerPlayer)");
            remove.invoke(playerList, nmsPlayer);
        } catch (Throwable t) {
            plugin.getLogger().warning("假人 " + bot.getName() + " 从在线列表移除失败：" + t);
        }

        // 再确保实体从世界里消失（已经在移除流程里被清掉时会抛异常，忽略即可）
        try {
            bot.remove();
        } catch (Throwable ignored) {
            // 已经从世界移除
        }
    }

    /**
     * 复活一个已死亡的假人。
     * <p>
     * 走服务端自己的 {@code PlayerList#respawn}：它会重建实体、送回出生点/床，
     * 并重置死亡状态。返回的新实体 uuid 不变，因此 BotManager 里按 uuid 存的记录继续有效。
     *
     * @return 是否成功发起复活
     */
    static boolean respawn(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        Object playerList = playerList(plugin, bot);
        if (playerList == null) return false;
        try {
            Class<?> reasonClass = clazz(REMOVAL_REASON);
            Object killed = enumValue(reasonClass, "KILLED");
            Object respawnReason = org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.DEATH;
            Method respawn = method(playerList.getClass(), "respawn",
                    clazz(NMS_PLAYER), boolean.class, reasonClass,
                    org.bukkit.event.player.PlayerRespawnEvent.RespawnReason.class);
            if (respawn == null) {
                warnOnce(plugin, "respawn", "签名不符");
                return false;
            }
            respawn.invoke(playerList, nmsPlayer, false, killed, respawnReason);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "respawn", t);
            return false;
        }
    }

    /**
     * 清空假连接上积压的待发送数据包。
     * <p>
     * EmbeddedChannel 会把服务端要发给「客户端」的包堆在自己的 outbound 队列里，
     * 假人没有真正的客户端来消费，长期运行会一直堆积，所以要定期清一次。
     */
    static void drainPendingPackets(Player bot) {
        try {
            Object nmsPlayer = bot.getClass().getMethod("getHandle").invoke(bot);
            if (nmsPlayer == null) return;
            Object connection = nmsPlayer.getClass().getField("connection").get(nmsPlayer);
            if (connection == null) return;
            Object channel = connection.getClass().getField("channel").get(connection);
            if (channel == null) return;
            Object queue = channel.getClass().getMethod("outboundMessages").invoke(channel);
            if (queue instanceof Collection<?> collection) collection.clear();
        } catch (Throwable ignored) {
            // 清理失败不影响假人本身，下一轮再试
        }
    }

    /** 把来源玩家的皮肤（textures 属性）复制到目标 profile 上 */
    @SuppressWarnings("unchecked")
    private static void copyTextures(JavaPlugin plugin, Player source, Object targetProfile,
                                     Class<?> profileClass) {
        try {
            Object sourceProfile = source.getClass().getMethod("getProfile").invoke(source);
            if (sourceProfile == null) return;
            Object sourceProps = profileClass.getMethod("getProperties").invoke(sourceProfile);
            Object targetProps = profileClass.getMethod("getProperties").invoke(targetProfile);

            Method get = findMethod(sourceProps.getClass(), "get", 1);
            Method put = findMethod(targetProps.getClass(), "put", 2);
            if (get == null || put == null) return;

            Object textures = get.invoke(sourceProps, "textures");
            if (!(textures instanceof Collection<?> collection) || collection.isEmpty()) return;
            for (Object property : collection) {
                put.invoke(targetProps, "textures", property);
            }
        } catch (Throwable t) {
            // 皮肤只是外观，失败不影响假人本身
            plugin.getLogger().warning("复制假人皮肤失败（改用默认皮肤）：" + rootCause(t));
        }
    }

    // ==================== 动作：姿态 / 移动 ====================

    /** 直接改假人朝向（实体追踪器会向周围玩家广播转向） */
    static boolean setRotation(JavaPlugin plugin, Player bot, float yaw, float pitch) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Method setYaw = method(nmsPlayer.getClass(), "setYRot", float.class);
            Method setPitch = method(nmsPlayer.getClass(), "setXRot", float.class);
            if (setYaw == null || setPitch == null) {
                warnOnce(plugin, "setRotation", "setYRot/setXRot 不存在");
                return false;
            }
            setYaw.invoke(nmsPlayer, yaw);
            setPitch.invoke(nmsPlayer, pitch);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "setRotation", t);
            return false;
        }
    }

    /**
     * 把假人挪到指定坐标。
     * <p>
     * 26.3 实测：玩家的位移是**客户端权威**的 —— 服务端只接受客户端上报的位置
     * （{@code ServerboundMovePlayerPacket}），但对假人这种没有真实客户端的玩家，
     * 服务端会用位置校验把它拉回去（发包后位置纹丝不动）。
     * 所以这里直接改服务端实体坐标（{@code ServerPlayer#snapTo}），
     * 由实体追踪器把位移广播给周围玩家（追踪器每 tick 比对位置自行发包）。
     */
    static boolean moveTo(JavaPlugin plugin, Player bot, Location loc, boolean onGround) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Method snap = method(nmsPlayer.getClass(), "snapTo",
                    double.class, double.class, double.class);
            if (snap == null) {
                warnOnce(plugin, "moveTo", "snapTo 不存在");
                return false;
            }
            snap.invoke(nmsPlayer, loc.getX(), loc.getY(), loc.getZ());
            if (onGround) applyOnGround(plugin, nmsPlayer, loc);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "moveTo", t);
            return false;
        }
    }

    /**
     * 把假人「脚下有没有地」写回 NMS 的 {@code Entity#onGround}。
     * <p>
     * 26.3 实测：假人没有真实客户端，服务端永远收不到
     * {@code ServerboundMovePlayerPacket}，所以 {@code onGround} 一直停在 {@code false}。
     * 而原版 {@code Player#attack} 的**横扫判定**
     * （{@code isSweepAttack}：{@code fullStrengthAttack && !criticalAttack && !knockbackAttack
     * && onGround() && 已知位移足够小 && 主手是剑}）和暴击判定都要读这个字段 ——
     * 结果就是假人**永远打不出横扫**。移动 / 传送 / 攻击前调用本方法把状态校正过来。
     *
     * @return 同步后的 onGround（假人不在线或反射失败时返回 false）
     */
    static boolean syncOnGround(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        return applyOnGround(plugin, nmsPlayer, bot.getLocation());
    }

    /** 反射写入 onGround；值由 {@link #standingOnBlock(Location)} 判定 */
    private static boolean applyOnGround(JavaPlugin plugin, Object nmsPlayer, Location loc) {
        try {
            Method set = method(nmsPlayer.getClass(), "setOnGround", boolean.class);
            if (set == null) {
                warnOnce(plugin, "syncOnGround", "Entity#setOnGround(boolean) 不存在");
                return false;
            }
            boolean grounded = standingOnBlock(loc);
            set.invoke(nmsPlayer, grounded);
            return grounded;
        } catch (Throwable t) {
            warnOnce(plugin, "syncOnGround", t);
            return false;
        }
    }

    /**
     * 脚底有没有方块顶着（近似服务端碰撞判定）：取脚下方块碰撞箱的顶面，
     * 与脚底 y 差距在 0.1 格以内即视为站立（能兼容台阶 / 半砖 / 地毯）。
     */
    private static boolean standingOnBlock(Location loc) {
        World world = loc.getWorld();
        if (world == null) return false;
        double feet = loc.getY();
        int x = NumberConversions.floor(loc.getX());
        int z = NumberConversions.floor(loc.getZ());
        Block block = world.getBlockAt(x, NumberConversions.floor(feet - 0.08), z);
        if (block.isPassable() || block.isLiquid()) return false;
        BoundingBox box = block.getBoundingBox();
        return box.getMaxY() >= feet - 0.1 && box.getMaxY() <= feet + 0.002;
    }

    // ==================== 动作：攻击 / 跳跃 / 睡觉 / 骑乘 ====================

    /**
     * 让假人按原版流程拾取掉落物。Paper 26.3 的 Mojang 映射签名为
     * {@code ItemEntity#playerTouch(net.minecraft.world.entity.player.Player)}；
     * 这里按参数可赋值关系查找，避免服务端小版本签名漂移。
     */
    static boolean pickupItem(JavaPlugin plugin, Player bot, Entity item) {
        Object nmsPlayer = nms(plugin, bot);
        Object nmsItem = nms(plugin, item);
        if (nmsPlayer == null || nmsItem == null) return false;
        try {
            Method playerTouch = null;
            for (Method candidate : nmsItem.getClass().getMethods()) {
                if (!candidate.getName().equals("playerTouch")
                        || candidate.getParameterCount() != 1
                        || !candidate.getParameterTypes()[0].isAssignableFrom(nmsPlayer.getClass())) continue;
                playerTouch = candidate;
                break;
            }
            if (playerTouch == null) {
                warnOnce(plugin, "pickupItem", "ItemEntity#playerTouch(Player) 不存在");
                return false;
            }
            playerTouch.invoke(nmsItem, nmsPlayer);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "pickupItem", t);
            return false;
        }
    }

    /** 攻击指定实体（NMS {@code Player#attack} + 挥手动画） */
    static boolean attack(JavaPlugin plugin, Player bot, Entity target) {
        Object nmsPlayer = nms(plugin, bot);
        Object nmsTarget = nms(plugin, target);
        if (nmsPlayer == null || nmsTarget == null) return false;
        try {
            Method attack = method(nmsPlayer.getClass(), "attack", clazz(NMS_ENTITY));
            if (attack == null) {
                warnOnce(plugin, "attack", "Player#attack 不存在");
                return false;
            }
            attack.invoke(nmsPlayer, nmsTarget);
            bot.swingMainHand();
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "attack", t);
            return false;
        }
    }

    /**
     * 把攻击充能顶满。
     * <p>
     * 假人没有真实连接，服务端不会调用 {@code ServerPlayer#tick()}，而
     * {@code attackStrengthTicker} 只在 {@code Player#tick()} 里自增 —— 结果它的
     * {@code getAttackCooldown()} 永远停在 0.1 左右：原版判定「没蓄满力」，
     * 既没有横扫、伤害也只有两成。攻击前直接把 ticker 顶到满充能
     * （{@code attack()} 内部会自行归零），冷却由 {@code BotActions} 自己计时。
     */
    static boolean chargeAttack(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Field ticker = field(nmsPlayer.getClass(), "attackStrengthTicker");
            if (ticker == null) {
                warnOnce(plugin, "chargeAttack", "attackStrengthTicker 字段不存在");
                return false;
            }
            ticker.setAccessible(true);
            ticker.setInt(nmsPlayer, 100);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "chargeAttack", t);
            return false;
        }
    }

    /** 跳一次（NMS {@code ServerPlayer#jumpFromGround}） */
    static boolean jump(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Method jump = method(nmsPlayer.getClass(), "jumpFromGround");
            if (jump == null) {
                warnOnce(plugin, "jump", "jumpFromGround 不存在");
                return false;
            }
            jump.invoke(nmsPlayer);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "jump", t);
            return false;
        }
    }

    /** 睡觉（NMS {@code startSleeping}，不校验床） */
    static boolean sleep(JavaPlugin plugin, Player bot, Location loc) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Class<?> posClass = clazz(NMS_BLOCKPOS);
            Method sleep = method(nmsPlayer.getClass(), "startSleeping", posClass);
            if (sleep == null) {
                warnOnce(plugin, "sleep", "startSleeping 不存在");
                return false;
            }
            Object pos = posClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
            sleep.invoke(nmsPlayer, pos);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "sleep", t);
            return false;
        }
    }

    /** 起床（NMS {@code Player#stopSleeping}） */
    static boolean wakeup(JavaPlugin plugin, Player bot) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        try {
            Method stop = method(nmsPlayer.getClass(), "stopSleeping");
            if (stop == null) {
                warnOnce(plugin, "wakeup", "stopSleeping 不存在");
                return false;
            }
            stop.invoke(nmsPlayer);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "wakeup", t);
            return false;
        }
    }

    /** 骑乘某个实体（NMS {@code Entity#startRiding(Entity)}，force=true） */
    static boolean ride(JavaPlugin plugin, Player bot, Entity vehicle) {
        Object nmsPlayer = nms(plugin, bot);
        Object nmsVehicle = nms(plugin, vehicle);
        if (nmsPlayer == null || nmsVehicle == null) return false;
        try {
            Method ride = method(nmsPlayer.getClass(), "startRiding", clazz(NMS_ENTITY));
            if (ride == null) {
                warnOnce(plugin, "ride", "startRiding 不存在");
                return false;
            }
            ride.invoke(nmsPlayer, nmsVehicle);
            return true;
        } catch (Throwable t) {
            warnOnce(plugin, "ride", t);
            return false;
        }
    }

    // ==================== 动作：使用物品 / 破坏方块 ====================

    /** 取假人 NMS 主手物品栈 */
    private static Object mainHandStack(JavaPlugin plugin, Player bot, Object nmsPlayer) {
        try {
            Method get = method(nmsPlayer.getClass(), "getMainHandItem");
            return get == null ? null : get.invoke(nmsPlayer);
        } catch (Throwable t) {
            warnOnce(plugin, "getMainHandItem", t);
            return null;
        }
    }

    /**
     * 右键「使用」手持物品（吃东西 / 拉弓 / 抛竿 / 桶……，不含朝方块的放置）。
     * 走服务端 {@code ServerPlayerGameMode#useItem}，与真客户端右键空气同路径。
     */
    static boolean useItem(JavaPlugin plugin, Player bot) {
        return useItem(plugin, bot, "MAIN_HAND");
    }

    /** 主/副手使用；返回值仅在服务端实际消费交互时为 true。 */
    static boolean useItem(JavaPlugin plugin, Player bot, String handName) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        Object gm = gameMode(plugin, bot, nmsPlayer);
        if (gm == null) return false;
        try {
            Method get = method(nmsPlayer.getClass(), handName.equals("OFF_HAND") ? "getOffhandItem" : "getMainHandItem");
            Object stack = get == null ? null : get.invoke(nmsPlayer);
            if (stack == null) return false;
            Object level = bot.getWorld().getClass().getMethod("getHandle").invoke(bot.getWorld());
            Method useItem = method(gm.getClass(), "useItem",
                    clazz(NMS_PLAYER), clazz("net.minecraft.world.level.Level"),
                    clazz(NMS_ITEMSTACK), clazz(NMS_HAND));
            if (useItem == null) {
                warnOnce(plugin, "useItem", "useItem 不存在");
                return false;
            }
            Object result = useItem.invoke(gm, nmsPlayer, level, stack, enumValue(clazz(NMS_HAND), handName));
            return consumesAction(result);
        } catch (Throwable t) {
            warnOnce(plugin, "useItem", t);
            return false;
        }
    }

    private static boolean consumesAction(Object result) throws ReflectiveOperationException {
        return result != null && Boolean.TRUE.equals(clazz("net.minecraft.world.InteractionResult")
                .getMethod("consumesAction").invoke(result));
    }

    /**
     * 右键「使用/放置」在指定方块面上（放方块、开箱子、按按钮……）。
     * 走服务端 {@code ServerPlayerGameMode#useItemOn}，与真客户端右键方块同路径。
     *
     * @param block  命中的方块
     * @param face   进入面
     * @param hitPos 命中点坐标（用于 BlockHitResult）
     */
    static boolean useItemOn(JavaPlugin plugin, Player bot, org.bukkit.block.Block block,
                             BlockFace face, org.bukkit.util.Vector hitPos) {
        return useItemOn(plugin, bot, block, face, hitPos, "MAIN_HAND");
    }

    static boolean useItemOn(JavaPlugin plugin, Player bot, org.bukkit.block.Block block,
                             BlockFace face, org.bukkit.util.Vector hitPos, String handName) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        Object gm = gameMode(plugin, bot, nmsPlayer);
        if (gm == null) return false;
        if (block == null || face == null) return false;
        try {
            Method get = method(nmsPlayer.getClass(), handName.equals("OFF_HAND") ? "getOffhandItem" : "getMainHandItem");
            Object stack = get == null ? null : get.invoke(nmsPlayer);
            if (stack == null) return false;
            Object level = bot.getWorld().getClass().getMethod("getHandle").invoke(bot.getWorld());

            Class<?> posClass = clazz(NMS_BLOCKPOS);
            Class<?> dirClass = clazz(NMS_DIRECTION);
            Class<?> vec3Class = clazz(NMS_VEC3);
            Class<?> hitClass = clazz(NMS_BLOCK_HIT);
            Object nmsPos = posClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(block.getX(), block.getY(), block.getZ());
            Object direction = enumValue(dirClass, face.name());
            org.bukkit.util.Vector p = hitPos == null ? block.getLocation().toVector() : hitPos;
            Object nmsVec = vec3Class.getConstructor(double.class, double.class, double.class)
                    .newInstance(p.getX(), p.getY(), p.getZ());
            Object nmsHit = hitClass.getConstructor(vec3Class, dirClass, posClass, boolean.class)
                    .newInstance(nmsVec, direction, nmsPos, false);

            Method useItemOn = method(gm.getClass(), "useItemOn",
                    clazz(NMS_PLAYER), clazz("net.minecraft.world.level.Level"),
                    clazz(NMS_ITEMSTACK), clazz(NMS_HAND), hitClass);
            if (useItemOn == null) {
                warnOnce(plugin, "useItemOn", "useItemOn 不存在");
                return false;
            }
            Object result = useItemOn.invoke(gm, nmsPlayer, level, stack, enumValue(clazz(NMS_HAND), handName), nmsHit);
            return consumesAction(result);
        } catch (Throwable t) {
            warnOnce(plugin, "useItemOn", t);
            return false;
        }
    }

    /**
     * 直接破坏方块（服务端 {@code ServerPlayerGameMode#destroyBlock}）。
     * <p>
     * 掉落物、{@code BlockBreakEvent}、统计都走服务端正常流程；假人的挖掘由
     * {@link BotActions} 按方块硬度计时后调到这里（26.3 实测：发客户端挖掘包
     * 不会让服务端推进挖掘进度）。
     */
    static boolean destroyBlock(JavaPlugin plugin, Player bot, org.bukkit.block.Block block) {
        Object nmsPlayer = nms(plugin, bot);
        if (nmsPlayer == null) return false;
        Object gm = gameMode(plugin, bot, nmsPlayer);
        if (gm == null) return false;
        try {
            Class<?> posClass = clazz(NMS_BLOCKPOS);
            Object nmsPos = posClass.getConstructor(int.class, int.class, int.class)
                    .newInstance(block.getX(), block.getY(), block.getZ());
            Method destroy = method(gm.getClass(), "destroyBlock", posClass);
            if (destroy == null) {
                warnOnce(plugin, "destroyBlock", "destroyBlock 不存在");
                return false;
            }
            return (Boolean) destroy.invoke(gm, nmsPos);
        } catch (Throwable t) {
            warnOnce(plugin, "destroyBlock", t);
            return false;
        }
    }

    /** 鱼漂是否正在「咬钩」（NMS 私有字段 biting，反射读取） */
    static boolean isBiting(JavaPlugin plugin, org.bukkit.entity.FishHook hook) {
        Object nmsHook = nms(plugin, hook);
        if (nmsHook == null) return false;
        try {
            Field biting = field(nmsHook.getClass(), "biting");
            if (biting == null) {
                warnOnce(plugin, "biting", "biting 字段不存在");
                return false;
            }
            biting.setAccessible(true);
            return (Boolean) biting.get(nmsHook);
        } catch (Throwable t) {
            warnOnce(plugin, "biting", t);
            return false;
        }
    }
}
