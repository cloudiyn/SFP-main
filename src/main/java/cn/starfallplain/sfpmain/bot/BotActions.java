package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.module.BotConfig;
import org.bukkit.Location;
import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.FishHook;
import org.bukkit.entity.Item;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.NumberConversions;
import org.bukkit.util.Vector;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 假人的动作系统：持续动作的周期执行 + 一次性动作。
 * <p>
 * <b>持续动作</b>（{@link BotAction}）：{@code attack} / {@code mine} / {@code use} / {@code move}
 * 每隔 {@code bot.yml} 的 {@code action-interval-ticks} 执行一次，与
 * minecraft-fakeplayer 的 periodical actions 对齐；{@code /sfp player stop} 全部停止。
 * <p>
 * <b>设置驱动的循环</b>（不需要显式开动作，改设置即生效）：
 * {@code wolverine}（超级回复）、{@code replenish}（自动补充快捷栏）、
 * {@code autofish}（自动钓鱼）。
 * <p>
 * <b>一次性动作</b>：jump / look / turn / sneak / sprint / ride / sleep / wakeup / respawn，
 * 由 {@link BotCommand} 直接调用本类方法。
 * <p>
 * 所有动作最终都落到 {@link NmsBotFactory} 的反射调用上：要么构造服务端数据包走
 * {@code ServerGamePacketListenerImpl} 的 public handle 方法（与真客户端同路径），
 * 要么直接调 ServerPlayer / ServerPlayerGameMode 的 public 方法。
 */
public final class BotActions {

    /** 挖掘状态：目标方块 + 已经「挖」了多少个动作周期（按硬度计时，到点直接破坏） */
    private final Map<UUID, MineState> mining = new ConcurrentHashMap<>();
    /** 自动钓鱼状态：抛竿时的 tick 与超时 tick */
    private final Map<UUID, FishState> fishing = new ConcurrentHashMap<>();
    /** 每只手最近一次使用前的物品模板，用于按 isSimilar 精确补充。 */
    private final Map<UUID, HandTemplates> handTemplates = new ConcurrentHashMap<>();

    private static final class HandTemplates {
        ItemStack main;
        ItemStack off;
        int mainSlot = -1;
    }

    private static final class MineState {
        final Block block;
        int ticks;

        MineState(Block block) {
            this.block = block;
        }
    }

    private record FishState(long castTick, long deadlineTick) {
    }

    private final SfpMain plugin;
    private final BotManager manager;
    private final BotConfig config;
    private final int interval;
    /** 自动钓鱼超时换算成「动作 tick 数」 */
    private final long fishTimeoutTicks;
    private BukkitTask task;
    /** 移动用的 1 tick 任务（每 tick 推进一小步，与真客户端发包频率一致） */
    private BukkitTask moveTask;
    /** 任务已执行的 tick 数（用于各设置循环的分频） */
    private long ticks;
    /**
     * 每个假人下一次允许攻击的动作周期。
     * 假人不会被 {@code ServerPlayer#tick()} 驱动，原版攻击冷却永远不满，
     * 所以这里按「12 tick ≈ 0.6 秒（剑类满充能）」自己折算成动作周期。
     */
    private final Map<UUID, Long> attackReadyAt = new HashMap<>();
    private final int attackCooldownCycles;

    public BotActions(SfpMain plugin, BotManager manager, BotConfig config) {
        this.plugin = plugin;
        this.manager = manager;
        this.config = config;
        this.interval = Math.max(1, config.getActionIntervalTicks());
        this.fishTimeoutTicks = Math.max(5, config.getAutofishTimeoutSeconds() * 20L / interval);
        this.attackCooldownCycles = Math.max(1, Math.round(12f / interval));
    }

    /** 启动周期任务（动作 4 tick 一次；移动需要每 tick 一步，走独立的 1 tick 任务） */
    public void start() {
        if (task != null) return;
        task = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::tick, interval, interval);
        moveTask = plugin.getServer().getScheduler()
                .runTaskTimer(plugin, this::moveStep, 1L, 1L);
    }

    /** 停掉周期任务 */
    public void shutdown() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (moveTask != null) {
            moveTask.cancel();
            moveTask = null;
        }
        mining.clear();
        fishing.clear();
        handTemplates.clear();
        attackReadyAt.clear();
    }

    // ==================== 周期任务 ====================

    private void tick() {
        ticks++;
        for (BotManager.Bot bot : manager.all()) {
            Player p = manager.online(bot);
            if (p == null || p.isDead()) continue;

            // ---- 设置驱动的循环 ----
            boolean replenishEnabled = Boolean.TRUE.equals(bot.settings.get(BotSettings.REPLENISH));
            if (replenishEnabled) rememberHands(p);
            if (Boolean.TRUE.equals(bot.settings.get(BotSettings.WOLVERINE))
                    && ticks % Math.max(1, config.getWolverineIntervalTicks() / interval) == 0) {
                heal(p);
            }
            if (Boolean.TRUE.equals(bot.settings.get(BotSettings.REPLENISH))
                    && ticks % Math.max(1, config.getReplenishIntervalTicks() / interval) == 0) {
                replenish(p);
            }
            if (Boolean.TRUE.equals(bot.settings.get(BotSettings.AUTOFISH))) {
                autofish(p);
                if (replenishEnabled) replenish(p);
            }
            if (Boolean.TRUE.equals(bot.settings.get(BotSettings.PICKUP_ITEMS))) {
                pickupItems(p);
            }

            // ---- 持续动作 ----
            if (bot.actions.isEmpty()) continue;
            if (bot.actions.contains(BotAction.ATTACK)) attackTick(p, bot);
            if (bot.actions.contains(BotAction.MINE)) mineTick(p);
            if (bot.actions.contains(BotAction.USE)) useTick(p);
        }
    }

    /** 移动：每 tick 给所有开着 MOVE 的假人推进一小步 */
    private void moveStep() {
        for (BotManager.Bot bot : manager.all()) {
            if (!bot.actions.contains(BotAction.MOVE)) continue;
            Player p = manager.online(bot);
            if (p == null || p.isDead()) continue;
            step(p);
        }
    }

    /** 持续攻击：自动锁定附近实体，look_at_entity 开启时先转身 */
    private void attackTick(Player bot, BotManager.Bot record) {
        double range = config.getAttackRange();
        LivingEntity best = null;
        double bestD = range * range;
        // 盔甲架单独挑：有真正的生物时优先打生物（装饰用的盔甲架不该抢目标）
        ArmorStand stand = null;
        double standD = range * range;
        for (Entity e : bot.getNearbyEntities(range, range, range)) {
            if (e instanceof ArmorStand as) {
                if (as.isDead() || as.equals(bot)) continue;
                // 原版 isAttackable = !isMarker && !isNoGravity：标记用的 / 关掉重力的盔甲架打不动
                if (as.isMarker() || !as.hasGravity()) continue;
                double d = as.getLocation().distanceSquared(bot.getLocation());
                if (d <= standD) {
                    standD = d;
                    stand = as;
                }
                continue;
            }
            if (!(e instanceof LivingEntity le) || le.isDead() || le.equals(bot)) continue;
            // 不打其他假人（避免假人互殴刷物品/消息）
            if (manager.isBot(le.getUniqueId())) continue;
            // 默认只打怪物与动物，不碰玩家（bot.yml 的 attack-players 可改）
            if (le instanceof Player && !config.isAttackPlayers()) continue;
            double d = le.getLocation().distanceSquared(bot.getLocation());
            if (d <= bestD) {
                bestD = d;
                best = le;
            }
        }
        LivingEntity target = best != null ? best : stand;
        if (target == null) return;
        if (Boolean.TRUE.equals(record.settings.get(BotSettings.LOOK_AT_ENTITY))) {
            lookAt(bot, target.getEyeLocation());
        }
        // 横扫 / 暴击判定都读 NMS 的 onGround，而假人没有客户端移动包 → 必须是 false，
        // 所以攻击前先按脚下的方块把 onGround 校正回来，否则假人永远打不出横扫。
        NmsBotFactory.syncOnGround(plugin, bot);
        // 原版攻击冷却读 attackStrengthTicker，而假人不会 tick（见 NmsBotFactory#chargeAttack）→ 自己计时。
        long readyAt = attackReadyAt.getOrDefault(bot.getUniqueId(), 0L);
        if (ticks < readyAt) return;
        attackReadyAt.put(bot.getUniqueId(), ticks + attackCooldownCycles);
        if (target instanceof ArmorStand) {
            // 原版盔甲架：第一下只算「打歪」（5 tick 内没有第二下就永远是这一下），
            // 5 tick 内的第二下才真正打碎。真人靠连点，假人一个动作周期直接连调两次。
            NmsBotFactory.chargeAttack(plugin, bot);
            NmsBotFactory.attack(plugin, bot, target);
            NmsBotFactory.attack(plugin, bot, target);
            return;
        }
        NmsBotFactory.chargeAttack(plugin, bot);
        NmsBotFactory.attack(plugin, bot, target);
    }

    /**
     * 假人视线命中的方块（自己走体素：{@code World#rayTraceBlocks} 对假人返回 null，
     * 实测在假人身上不可用）。
     *
     * @return 命中信息；视线前方没有实体方块时返回 null
     */
    private Target lookingAt(Player bot, double maxDistance) {
        Location eye = bot.getEyeLocation();
        Vector dir = eye.getDirection();
        double step = 0.05;
        Block prev = null;
        for (double d = 0; d <= maxDistance; d += step) {
            double x = eye.getX() + dir.getX() * d;
            double y = eye.getY() + dir.getY() * d;
            double z = eye.getZ() + dir.getZ() * d;
            Block block = bot.getWorld().getBlockAt(
                    NumberConversions.floor(x), NumberConversions.floor(y), NumberConversions.floor(z));
            if (block.isPassable() || block.isLiquid()) {
                prev = block;
                continue;
            }
            // 由「上一个采样点 → 当前方块」的变化轴推断从哪一面进入
            BlockFace face = BlockFace.UP;
            if (prev != null) {
                if (block.getX() > prev.getX()) face = BlockFace.WEST;
                else if (block.getX() < prev.getX()) face = BlockFace.EAST;
                else if (block.getZ() > prev.getZ()) face = BlockFace.NORTH;
                else if (block.getZ() < prev.getZ()) face = BlockFace.SOUTH;
                else if (block.getY() > prev.getY()) face = BlockFace.DOWN;
                else if (block.getY() < prev.getY()) face = BlockFace.UP;
            }
            return new Target(block, face, new Vector(x, y, z));
        }
        return null;
    }

    /** 视线命中的方块 / 进入面 / 命中点 */
    private record Target(Block block, BlockFace face, Vector hitPos) {
    }

    /**
     * 持续挖掘：挖假人面朝方向的方块。
     * <p>
     * 26.3 实测：向假人连接发送 {@code ServerboundPlayerActionPacket}（客户端挖掘包）
     * **不会**让服务端推进挖掘进度（玩家的方块交互同样是客户端权威的），所以这里改成
     * 「自己按硬度计时，到点调用 {@code ServerPlayerGameMode#destroyBlock} 破坏」：
     * 掉落物、{@code BlockBreakEvent}、统计都走服务端正常流程，只是没有原版裂纹动画。
     */
    private void mineTick(Player bot) {
        Target target = lookingAt(bot, 5.0);
        if (target == null) {
            stopMining(bot);
            return;
        }
        Block block = target.block();
        float hardness = block.getType().getHardness();
        if (hardness < 0) { // 基岩等不可破坏方块
            stopMining(bot);
            return;
        }
        UUID id = bot.getUniqueId();
        // 创造模式没有挖掘过程，直接破坏
        if (bot.getGameMode() == GameMode.CREATIVE) {
            NmsBotFactory.destroyBlock(plugin, bot, block);
            mining.remove(id);
            return;
        }
        MineState state = mining.get(id);
        if (state == null || !sameBlock(state.block, block)) {
            mining.put(id, new MineState(block));
            return;
        }
        state.ticks++;
        if (state.ticks * (long) interval < requiredTicks(bot, block, hardness)) return;
        NmsBotFactory.destroyBlock(plugin, bot, block);
        mining.remove(id);
    }

    /**
     * 挖掉这个方块需要的 tick 数。
     * <p>
     * {@code Block#getBreakSpeed(Player)} 在 Paper 26.3 返回的其实是**每 tick 的破坏进度**
     * （实测：徒手挖箱子 ≈ 0.0133/tick，正好是原版 2.5 硬度 75 tick 的倒数），
     * 所以直接按 {@code ceil(1 / 进度)} 换算。取不到时退回硬度公式（徒手/工具不对 ×100）。
     */
    private int requiredTicks(Player bot, Block block, float hardness) {
        float progress = block.getBreakSpeed(bot);
        if (progress <= 0) {
            boolean harvest = block.isPreferredTool(bot.getInventory().getItemInMainHand());
            progress = 1.0f / (hardness * (harvest ? 30.0f : 100.0f));
        }
        return (int) Math.ceil(1.0 / progress);
    }

    /** 持续使用：视线命中方块 → 放置/交互；否则 → 使用手持物品 */
    private void useTick(Player bot) {
        rememberHands(bot);
        Target target = lookingAt(bot, 5.0);
        boolean consumed = false;
        for (String hand : new String[]{"MAIN_HAND", "OFF_HAND"}) {
            if (target != null) consumed = NmsBotFactory.useItemOn(plugin, bot, target.block(), target.face(), target.hitPos(), hand);
            if (!consumed) consumed = NmsBotFactory.useItem(plugin, bot, hand);
            if (consumed) break;
        }
        replenishAfterUse(bot);
    }

    /**
     * 朝面朝方向走一小步（每 tick 调用）。
     * <p>
     * 26.3 实测：玩家的位移由**客户端上报**，服务端只做接受与校验 —— 所以这里不是
     * 「给假人加个速度」，而是自己算出下一步坐标，再用 {@code ServerboundMovePlayerPacket$PosRot}
     * 上报（真客户端每 tick 做的同一件事）。
     * <p>
     * 碰撞做最简近似：脚 / 头两个方块必须可穿过，否则尝试抬 0.6 格上台阶；脚下悬空时
     * 每 tick 最多下落 1 格（贴地行走，不做游泳 / 攀爬）。
     */
    private void step(Player bot) {
        Location loc = bot.getLocation();
        // 只按 yaw 算水平方向：玩家走路不受俯仰角影响，且俯视时 getDirection() 的
        // 水平分量会是 0（之前就是这样让 move 直接没动的）
        double yaw = Math.toRadians(loc.getYaw());
        Vector dir = new Vector(-Math.sin(yaw), 0, Math.cos(yaw));
        double speed = bot.isSprinting() ? 0.28 : 0.216;

        Location next = loc.clone().add(dir.getX() * speed, 0, dir.getZ() * speed);
        // 前方被挡住 → 尝试抬一格（上一格台阶），仍然挡住就不动（不做绕路）
        if (!passable(next)) {
            next.add(0, 1.0, 0);
            if (!passable(next)) return;
        }
        // 贴地：找前方脚下最近的地面（±4 格内），避免掉进洞/虚空后一路往下沉
        double ground = groundY(next.getWorld(), next);
        if (!Double.isNaN(ground)) next.setY(ground);
        NmsBotFactory.moveTo(plugin, bot, next, true);
    }

    /**
     * 找该位置前方最近的「可站立地面」高度：从当前 y+1 往下扫最多 5 格，
     * 第一个实心方块的上表面就是落脚高度。
     *
     * @return 落脚 y；附近没有地面时返回 NaN（保持原高度）
     */
    private double groundY(World world, Location loc) {
        int x = NumberConversions.floor(loc.getX());
        int z = NumberConversions.floor(loc.getZ());
        int start = NumberConversions.floor(loc.getY()) + 1;
        for (int y = start; y >= start - 5; y--) {
            Block block = world.getBlockAt(x, y, z);
            if (!block.isPassable() && !block.isLiquid()) return y + 1.0;
        }
        return Double.NaN;
    }

    /** 判断两个 Block 是否同一格（CraftBlock 没有覆写 equals，只能比坐标） */
    private static boolean sameBlock(Block a, Block b) {
        return a.getWorld().equals(b.getWorld())
                && a.getX() == b.getX() && a.getY() == b.getY() && a.getZ() == b.getZ();
    }

    /** 脚与头两个方块都可穿过 */
    private boolean passable(Location loc) {
        World world = loc.getWorld();
        Block feet = world.getBlockAt(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        Block head = world.getBlockAt(loc.getBlockX(), loc.getBlockY() + 1, loc.getBlockZ());
        return feet.isPassable() && head.isPassable();
    }

    // ==================== 设置驱动的循环 ====================

    /** 超级回复：生命 / 饱食度回满 */
    private void heal(Player bot) {
        var attribute = bot.getAttribute(Attribute.MAX_HEALTH);
        double max = attribute != null ? attribute.getValue() : 20.0;
        if (bot.getHealth() < max) bot.setHealth(max);
        bot.setFoodLevel(20);
        bot.setSaturation(20f);
        bot.setExhaustion(0f);
    }

    /**
     * 通过 ItemEntity 原版 playerTouch 流程拾取附近掉落物。
     * 不手动改背包，确保拾取事件、堆叠、拾取延迟与统计保持原版行为。
     */
    private void pickupItems(Player bot) {
        for (Item item : bot.getNearbyEntities(1.5, 1.5, 1.5).stream()
                .filter(Item.class::isInstance).map(Item.class::cast).toList()) {
            if (!item.isDead() && item.getPickupDelay() <= 0
                    && config.isPickupAllowed(item.getItemStack().getType())) {
                NmsBotFactory.pickupItem(plugin, bot, item);
            }
        }
    }

    /**
     * 按最近一次使用前的主手/副手模板补充；只从背包储物格取同类物品，
     * 不跨手位挪用，也不把任意物品塞进空快捷栏。
     */
    private void replenish(Player bot) {
        HandTemplates templates = handTemplates.get(bot.getUniqueId());
        if (templates == null) return;
        PlayerInventory inv = bot.getInventory();
        refillHand(inv, true, templates.main);
        refillHand(inv, false, templates.off);
    }

    private static boolean isDepleted(ItemStack item) {
        return item == null || item.getType().isAir() || item.getAmount() <= 0;
    }

    private static void refillHand(PlayerInventory inv, boolean main, ItemStack template) {
        if (template == null || template.getType().isAir()) return;
        ItemStack current = main ? inv.getItemInMainHand() : inv.getItemInOffHand();
        if (!isDepleted(current) && !current.isSimilar(template)) return;
        int amount = isDepleted(current) ? 0 : current.getAmount();
        ItemStack replacement = isDepleted(current) ? template.clone() : current.clone();
        int limit = replacement.getMaxStackSize();
        for (int src = 9; src < 36 && amount < limit; src++) {
            ItemStack candidate = inv.getItem(src);
            if (isDepleted(candidate) || !candidate.isSimilar(template)) continue;
            int moved = Math.min(limit - amount, candidate.getAmount());
            amount += moved;
            int left = candidate.getAmount() - moved;
            ItemStack remainder = candidate.clone();
            remainder.setAmount(left);
            inv.setItem(src, left == 0 ? null : remainder);
        }
        if (amount == 0) return;
        replacement.setAmount(amount);
        if (main) inv.setItemInMainHand(replacement);
        else inv.setItemInOffHand(replacement);
    }

    private void rememberHands(Player bot) {
        PlayerInventory inv = bot.getInventory();
        HandTemplates templates = handTemplates.computeIfAbsent(bot.getUniqueId(), ignored -> new HandTemplates());
        int slot = inv.getHeldItemSlot();
        if (templates.mainSlot != slot) {
            templates.mainSlot = slot;
            templates.main = null;
        }
        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        if (!isDepleted(main)) templates.main = main.clone();
        if (!isDepleted(off)) templates.off = off.clone();
    }

    private void replenishAfterUse(Player bot) {
        BotManager.Bot record = manager.byUuid(bot.getUniqueId());
        if (record != null && Boolean.TRUE.equals(record.settings.get(BotSettings.REPLENISH))) replenish(bot);
    }

    /**
     * 自动钓鱼（手持鱼竿时）：
     * 没有鱼漂 → 抛竿；鱼漂在水里且 {@code biting} → 收竿；
     * 超过 {@code autofish-timeout-seconds} 没咬钩 → 收竿重抛。
     */
    private void autofish(Player bot) {
        UUID uuid = bot.getUniqueId();
        ItemStack hand = bot.getInventory().getItemInMainHand();
        if (hand == null || hand.getType() != Material.FISHING_ROD) {
            fishing.remove(uuid);
            return;
        }
        FishHook hook = findHook(bot);
        if (hook == null) {
            FishState state = fishing.get(uuid);
            // 抛竿有冷却（避免鱼竿丢失/卡住时每 tick 猛抛）
            if (state == null || ticks - state.castTick() >= 10) {
                NmsBotFactory.useItem(plugin, bot);
                fishing.put(uuid, new FishState(ticks, 0));
            }
            return;
        }
        if (NmsBotFactory.isBiting(plugin, hook)) {
            hook.retrieve(EquipmentSlot.HAND);
            fishing.remove(uuid);
            return;
        }
        FishState state = fishing.get(uuid);
        if (state == null || state.deadlineTick() == 0) {
            fishing.put(uuid, new FishState(ticks, ticks + fishTimeoutTicks));
        } else if (ticks > state.deadlineTick()) {
            hook.retrieve(EquipmentSlot.HAND);
            fishing.remove(uuid);
        }
    }

    /** 找假人自己的鱼漂 */
    private FishHook findHook(Player bot) {
        World world = bot.getWorld();
        for (Entity e : world.getEntitiesByClass(FishHook.class)) {
            FishHook hook = (FishHook) e;
            if (bot.equals(hook.getShooter())) return hook;
        }
        return null;
    }

    // ==================== 一次性动作 ====================

    /** 跳一次 */
    public boolean jump(Player bot) {
        return NmsBotFactory.jump(plugin, bot);
    }

    /** 看向某个位置 */
    public boolean lookAt(Player bot, Location target) {
        double dx = target.getX() - bot.getX();
        double dy = target.getY() - (bot.getY() + bot.getEyeHeight());
        double dz = target.getZ() - bot.getZ();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return NmsBotFactory.setRotation(plugin, bot, yaw, pitch);
    }

    /** 原地转向（相对角度，正数右转） */
    public boolean turn(Player bot, double degrees) {
        Location loc = bot.getLocation();
        float yaw = loc.getYaw() + (float) degrees;
        // 归一化到 (-180, 180]
        yaw = (yaw + 180f) % 360f;
        if (yaw < 0) yaw += 360f;
        yaw -= 180f;
        return NmsBotFactory.setRotation(plugin, bot, yaw, loc.getPitch());
    }

    /** 潜行开关（Bukkit 层置位：更新姿势标志并广播给周围玩家） */
    public boolean sneak(Player bot, boolean on) {
        bot.setSneaking(on);
        return true;
    }

    /** 疾跑开关（Bukkit 层置位：更新疾跑标志并广播） */
    public boolean sprint(Player bot, boolean on) {
        bot.setSprinting(on);
        return true;
    }

    /** 骑乘某个实体 */
    public boolean ride(Player bot, Entity vehicle) {
        return NmsBotFactory.ride(plugin, bot, vehicle);
    }

    /** 睡觉（原地，不校验床） */
    public boolean sleep(Player bot) {
        return NmsBotFactory.sleep(plugin, bot, bot.getLocation());
    }

    /** 起床 */
    public boolean wakeup(Player bot) {
        return NmsBotFactory.wakeup(plugin, bot);
    }

    /** 复活死亡假人（复活后重放设置：respawn 会生成新实体） */
    public boolean respawn(Player bot, BotManager.Bot record) {
        boolean ok = NmsBotFactory.respawn(plugin, bot);
        if (ok) {
            manager.applySettings(record);
        }
        return ok;
    }

    // ==================== 状态变更 ====================

    /**
     * 持续动作被开关 / 假人设置变化时调用，清理与新状态冲突的旧状态。
     *
     * @param action 变化的动作；{@code null} 表示「全部停止」
     * @param on     true = 开启，false = 关闭
     */
    public void onActionChanged(Player bot, BotManager.Bot record, BotAction action, boolean on) {
        if (action == null || action == BotAction.MINE) {
            stopMining(bot);
        }
        // MOVE 由独立的 1 tick 任务驱动，开关动作不需要额外处理
    }

    /** 假人被移除：清掉它的挖掘 / 钓鱼状态 */
    public void onBotRemoved(UUID botUuid) {
        mining.remove(botUuid);
        fishing.remove(botUuid);
    }

    private void stopMining(Player bot) {
        mining.remove(bot.getUniqueId());
    }

    // ==================== 展示 ====================

    /** 假人当前的持续动作描述（供 list / status 展示） */
    public String describe(BotManager.Bot bot) {
        if (bot.actions.isEmpty()) return "无";
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (BotAction action : bot.actions) {
            if (!first) sb.append('|');
            sb.append(action.display());
            first = false;
        }
        return sb.toString();
    }

    /** 全部在线假人的动作名（供 PAPI 占位符 %fakeplayer_actions_<名字>% 使用） */
    public String describeByName(String botName) {
        for (BotManager.Bot bot : manager.all()) {
            if (bot.name.equalsIgnoreCase(botName)) return describe(bot);
        }
        return "";
    }
}
