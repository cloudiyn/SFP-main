package cn.starfallplain.sfpmain.chain;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.ChainConfig;
import cn.starfallplain.sfpmain.util.SoundUtil;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.event.block.BlockBreakEvent;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 连锁挖矿：玩家蹲下挖掉一块矿时，把相连的同种矿一并挖掉，并按方块量扣饱食度。
 *
 * <h2>为什么拦在 {@code BlockBreakEvent} 里</h2>
 * 连锁的每一块都必须<b>真正触发一次 {@link BlockBreakEvent}</b>（挖完再手动
 * {@code setType(AIR)} + 自造掉落），否则会静默丢掉三样东西：
 * <ul>
 *   <li><b>经验</b>：原版经验来自挖方块事件的结算，手动清空拿不到</li>
 *   <li><b>其他插件的监听</b>：领地保护、CoreProtect、统计类插件全靠这个事件，
 *       不触发就等于绕过了它们的保护</li>
 *   <li><b>工具耐久与采集等级</b>：钻石镐挖黑曜石、和普通镐挖石头掉落不同，
 *       这些都由原版结算路径决定</li>
 * </ul>
 * 也就是说「手动模拟挖方块」比「复制掉落物」正确得多。
 *
 * <h2>为什么只能同种方块</h2>
 * 矿石没有木头那样的树冠信号（{@code BlockData} 里只有 {@code axis}，
 * 读不出「这块铁矿石是自然生成的还是玩家摆的」），所以唯一的可靠闸门是
 * <b>只扩散同种方块 + 总量上限</b>。玩家在矿脉中间挖一块，
 * 连锁范围天然跟着矿脉形状走，不会漫延到旁边的建筑。
 */
public class ChainManager {

    private final SfpMain plugin;
    private final ChainConfig config;

    public ChainManager(SfpMain plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager().chain();
    }

    private Messages messages() {
        return plugin.getConfigManager().messages();
    }

    /**
     * 在玩家挖掉方块后尝试连锁。
     *
     * @param event 已经过其他插件处理、即将生效的挖方块事件（方块此时还在世界里）
     * @return 实际额外连锁挖掉的方块数；0 表示未连锁
     */
    public int handleBreak(BlockBreakEvent event) {
        if (!config.isEnabled()) return 0;

        Block origin = event.getBlock();
        Player player = event.getPlayer();

        if (!config.appliesToWorld(origin.getWorld().getName())) return 0;
        if (!isChainable(origin.getType())) return 0;
        // 假人挖矿默认不连锁：它是自动化工具人，连锁对它没有手感意义，
        // 却会让它瞬间吃光整条矿脉并扣掉饱食度
        if (!config.isChainBots() && isBot(player)) return 0;
        if (config.isSurvivalOnly() && player.getGameMode() != GameMode.SURVIVAL) return 0;
        if (config.isRequireSneak() && !player.isSneaking()) return 0;
        if (config.isRequireTool() && !isMiningTool(player)) return 0;

        // 连锁额外挖掉的方块（不含玩家亲手挖掉的第一块）
        List<Block> targets = collect(origin);
        if (targets.isEmpty()) return 0;

        // 收费：按方块量扣饱食度。扣不动就只保留第一块，不连锁
        int affordable = affordableCount(player, targets.size());
        if (affordable <= 0) {
            messages().send(player, "chain.not-enough-food",
                    "<red>饱食度不足，无法连锁挖矿。</red>", Map.of());
            return 0;
        }
        if (affordable < targets.size()) {
            targets = targets.subList(0, affordable);
            messages().send(player, "chain.partial",
                    "<gray>饱食度只够连锁 {count} 块，本次连锁部分生效。</gray>",
                    Map.of("count", String.valueOf(affordable)));
        }

        int broken = 0;
        for (Block block : targets) {
            // 扩散期间方块可能已被别的插件（例如连锁本身之外的逻辑）挖掉
            if (block.getType().isAir() || block.getType() != origin.getType()) continue;
            if (!breakNaturally(player, block)) continue;
            broken++;
            if (config.isSoundEnabled() && config.isSoundPerBlock()) {
                playSound(player, block);
            }
        }
        if (broken == 0) return 0;

        // 收费按「实际连锁掉的数量」结算，避免扣了钱却没挖到东西
        chargeFood(player, broken);

        if (config.isSoundEnabled() && !config.isSoundPerBlock()) {
            playSound(player, origin);
        }
        if (config.isMessageEnabled()) {
            messages().send(player, "chain.broken",
                    "<green>连锁挖矿：额外挖掉 <white>{count}</white> 块 <gray>(-{food} 饱食度)</gray>。</green>",
                    Map.of("count", String.valueOf(broken),
                            "food", formatFood(broken * config.getExhaustionPerBlock())));
        }
        return broken;
    }

    /**
     * 从起点向外广度优先扩散，收集同种矿脉方块。
     * <p>
     * 扩散是 26 邻域（上下左右 + 4 个斜角）——矿脉常有对角走向，
     * 只用 6 邻域会把一脉矿切成好几段。同时<b>不跨越非矿方块</b>，
     * 所以范围天然被矿脉边界限制住。
     *
     * @return 额外要挖的方块（不含玩家亲手挖掉的起点方块）
     */
    private List<Block> collect(Block origin) {
        int max = config.getMaxBlocks();
        List<Block> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        Deque<Block> queue = new ArrayDeque<>();

        Material type = origin.getType();
        // 玩家亲手挖掉的起点方块不入结果，但要在去重集合里占位
        seen.add(key(origin.getX(), origin.getY(), origin.getZ()));
        queue.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (int dx : new int[]{-1, 0, 1}) {
                for (int dy : new int[]{-1, 0, 1}) {
                    for (int dz : new int[]{-1, 0, 1}) {
                        if (dx == 0 && dy == 0 && dz == 0) continue;
                        Location loc = current.getLocation().add(dx, dy, dz);
                        // 不跨越未加载区块：连锁完全不该引发区块加载风暴
                        if (!loc.getWorld().isChunkLoaded(loc.getBlockX() >> 4, loc.getBlockZ() >> 4)) continue;
                        Block next = loc.getBlock();
                        if (next.getType() != type) continue;
                        if (!seen.add(key(next.getX(), next.getY(), next.getZ()))) continue;
                        if (result.size() >= max) break;
                        result.add(next);
                        queue.add(next);
                    }
                }
            }
        }
        return result;
    }

    /** 位置打包成 long 作去重键（避免 Box 分配，26 邻域下这个数量不小） */
    private static long key(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /**
     * 用玩家的工具挖掉这一块，让原版完整结算经验、耐久、掉落与保护检查。
     * <p>
     * 关键点：<b>只调 {@code setType(AIR)} 自己清空，再手动投放掉落物</b>，
     * 不能用 {@code block.breakNaturally(tool)} —— 那个方法自己也会丢一遍，
     * 叠加就成了双份掉落。
     *
     * @return 是否真的挖掉了（被领地保护等插件取消时返回 false）
     */
    private boolean breakNaturally(Player player, Block block) {
        ItemStack tool = player.getInventory().getItemInMainHand();

        BlockBreakEvent event = new BlockBreakEvent(block, player);
        plugin.getServer().getPluginManager().callEvent(event);
        if (event.isCancelled()) return false;

        Material type = block.getType();
        // 先算掉落与经验，再清空方块：清空后 getDrops 就拿不到东西了
        List<ItemStack> drops = event.isDropItems()
                ? new ArrayList<>(block.getDrops(tool, player))
                : List.of();
        int exp = config.isGiveExperience() ? event.getExpToDrop() : 0;

        block.setType(Material.AIR, false);

        Location center = block.getLocation().add(0.5, 0.5, 0.5);
        for (ItemStack drop : drops) {
            if (drop != null && !drop.getType().isAir()) {
                block.getWorld().dropItemNaturally(center, drop);
            }
        }
        if (exp > 0) player.giveExp(exp);

        // 耐久：用 26.3 的 ItemStack#damage(int, LivingEntity)。
        // 它返回新对象（内部已处理「无法损坏」标记与耐久耗尽；26.3 起
        // Unbreakable 不再是 meta 类，改由 data component 承载，别自己去翻 meta），
        // 所以必须写回主手才生效 —— 直接改原对象不会生效。
        if (!tool.getType().isAir() && tool.getType().getMaxDurability() > 0) {
            ItemStack damaged = tool.damage(1, player);
            player.getInventory().setItemInMainHand(damaged == null ? tool : damaged);
        }
        return true;
    }

/** 当前手持物是不是镐 / 斧 / 锹 / 锄 */
    private boolean isMiningTool(Player player) {
        Material type = player.getInventory().getItemInMainHand().getType();
        return type == Material.DIAMOND_PICKAXE || type == Material.NETHERITE_PICKAXE
                || type == Material.IRON_PICKAXE || type == Material.STONE_PICKAXE
                || type == Material.GOLDEN_PICKAXE || type == Material.WOODEN_PICKAXE
                || type == Material.DIAMOND_AXE || type == Material.NETHERITE_AXE
                || type == Material.IRON_AXE || type == Material.STONE_AXE
                || type == Material.GOLDEN_AXE || type == Material.WOODEN_AXE
                || type == Material.DIAMOND_SHOVEL || type == Material.NETHERITE_SHOVEL
                || type == Material.IRON_SHOVEL || type == Material.STONE_SHOVEL
                || type == Material.GOLDEN_SHOVEL || type == Material.WOODEN_SHOVEL
                || type == Material.DIAMOND_HOE || type == Material.NETHERITE_HOE
                || type == Material.IRON_HOE || type == Material.STONE_HOE
                || type == Material.GOLDEN_HOE || type == Material.WOODEN_HOE;
    }

    /** 是否本插件创建的假人（假人模块未启用时恒为 false） */
    private boolean isBot(Player player) {
        return plugin.getBotManager() != null
                && plugin.getBotManager().isBot(player.getUniqueId());
    }

    /** 该方块是否在连锁白名单内 */
    public boolean isChainable(Material type) {
        return config.getBlocks().contains(type.name());
    }

    /** 按当前饱食度算「最多能连锁几块」 */
    private int affordableCount(Player player, int wanted) {
        double per = config.getExhaustionPerBlock();
        if (per <= 0) return wanted;
        int food = player.getFoodLevel();
        if (config.isStopWhenHungry() && food <= 0) return 0;
        // 直接扣点数会取下整（饱食度是 int），按同样口径估算能扣几块
        double costPerBlock = Math.max(1.0, per);
        int affordable = (int) Math.floor(food / costPerBlock);
        return Math.max(0, Math.min(wanted, affordable));
    }

    /** 实际扣饱食度：逐块扣，每块取下整，保证扣完不发负 */
    private void chargeFood(Player player, int blocks) {
        double per = config.getExhaustionPerBlock();
        if (per <= 0) return;
        int food = player.getFoodLevel();
        long cost = Math.round(blocks * per);
        if (cost <= 0) return;
        int next = (int) Math.max(0, food - cost);
        player.setFoodLevel(next);
        // 饱食度直接掉了，饱和度按比例跟着降，避免出现「饿度低但饱和度满」的不一致
        if (next == 0) {
            player.setSaturation(0f);
        } else if (food > 0) {
            float saturation = player.getSaturation() * (next / (float) food);
            player.setSaturation(Math.max(0f, saturation));
        }
    }

    private void playSound(Player player, Block block) {
        Sound sound = SoundUtil.resolve(config.getSoundName());
        if (sound == null) return;
        player.playSound(block.getLocation(), sound, config.getSoundVolume(), config.getSoundPitch());
    }

    private String formatFood(double value) {
        String text = String.format(java.util.Locale.ROOT, "%.1f", value);
        // 整数值显示成 "3" 而不是 "3.0"
        if (text.endsWith(".0")) return text.substring(0, text.length() - 2);
        return text;
    }
}