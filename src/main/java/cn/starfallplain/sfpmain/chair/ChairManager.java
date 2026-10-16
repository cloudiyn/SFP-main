package cn.starfallplain.sfpmain.chair;

import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.ChairConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 椅子管理器。关键字、允许的木种、坐姿偏移、起身触发方式均来自 chair.yml。
 */
public class ChairManager {

    private final JavaPlugin plugin;
    private final ChairConfig config;
    private final Messages messages;
    private final Map<UUID, ArmorStand> sittingPlayers = new HashMap<>();
    private final Map<UUID, Block> chairBlocks = new HashMap<>();

    public ChairManager(JavaPlugin plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = configManager.chair();
        this.messages = configManager.messages();
    }

    public ChairConfig getConfig() {
        return config;
    }

    public boolean isSitting(Player player) {
        return sittingPlayers.containsKey(player.getUniqueId());
    }

    /** 当前坐在椅子上的玩家数量（供 /sfp status 与自检显示运行态） */
    public int sittingCount() {
        return sittingPlayers.size();
    }

    public Block getChairBlock(Player player) {
        return chairBlocks.get(player.getUniqueId());
    }

    /**
     * 检查方块是否正在被某玩家作为椅子使用
     */
    public boolean isBlockInUse(Block block) {
        for (Block b : chairBlocks.values()) {
            if (b.equals(block)) return true;
        }
        return false;
    }

    /**
     * 检查方块是否是有效椅子结构
     * 需要左右两侧均为木质告示牌，分别写着配置的左右关键字
     */
    public boolean isChairBlock(Block block) {
        return getChairFacingYaw(block) != null;
    }

    /**
     * 获取椅子朝向的 yaw 角度，若不是有效椅子返回 null
     */
    public Float getChairFacingYaw(Block block) {
        BlockFace leftFace = findLeftSignFace(block);
        if (leftFace == null) return null;
        return getYawFromFacing(getFacingFromLeft(leftFace));
    }

    /**
     * 让玩家坐在指定方块上
     */
    public void sitPlayer(Player player, Block block) {
        Float yaw = getChairFacingYaw(block);
        if (yaw == null) return;

        // 如果已经在坐着，先站起来
        if (isSitting(player)) {
            standPlayer(player);
        }

        // 根据方块实际高度计算坐姿 Y 轴位置（偏移量来自配置）
        double blockTopY = block.getBoundingBox().getMaxY();
        Location loc = block.getLocation();
        loc.setX(loc.getBlockX() + 0.5);
        loc.setY(blockTopY - config.getSeatHeightOffset());
        loc.setZ(loc.getBlockZ() + 0.5);
        loc.setYaw(yaw);
        loc.setPitch(0f);

        ArmorStand stand = (ArmorStand) block.getWorld().spawnEntity(loc, EntityType.ARMOR_STAND);
        stand.setGravity(false);
        stand.setVisible(false);
        stand.setSmall(true);
        stand.setInvulnerable(true);
        stand.customName(net.kyori.adventure.text.Component.text("stf-chair"));
        stand.setCustomNameVisible(false);

        stand.addPassenger(player);

        sittingPlayers.put(player.getUniqueId(), stand);
        chairBlocks.put(player.getUniqueId(), block);

        player.sendMessage(messages.component("chair.sit",
                "<green>你坐下了。潜行或再次右键座位可以站起来。</green>"));
    }

    /**
     * 让玩家从座位上站起来
     */
    public void standPlayer(Player player) {
        ArmorStand stand = sittingPlayers.remove(player.getUniqueId());
        chairBlocks.remove(player.getUniqueId());
        if (stand != null && !stand.isDead()) {
            stand.eject();
            stand.remove();
        }
    }

    /**
     * 玩家退出游戏时清理状态
     */
    public void onQuit(Player player) {
        standPlayer(player);
    }

    /**
     * 插件卸载 / 重载时清理：把所有还坐着的玩家扶起来，并移除隐形的盔甲架座位。
     * <p>
     * 不做这一步的话，「玩家还坐着时禁用插件」会留下孤儿盔甲架 —— {@code sittingPlayers}
     * 映射随本实例一起被丢弃，那些实体再也回收不掉（只能重启服务端或手动清理）。
     */
    public void shutdown() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (isSitting(player)) {
                standPlayer(player);
            }
        }
        // 兜底：映射里可能还有已下线的玩家（正常路径下 onQuit 已经清过）
        for (ArmorStand stand : sittingPlayers.values()) {
            if (stand != null && !stand.isDead()) {
                stand.eject();
                stand.remove();
            }
        }
        sittingPlayers.clear();
        chairBlocks.clear();
    }

    /**
     * 找到左侧关键字告示牌所在的朝向（同时验证对面是右侧关键字）
     */
    private BlockFace findLeftSignFace(Block block) {
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block adjacent = block.getRelative(face);
            if (!isWoodenSign(adjacent)) continue;
            String line = getSignFirstLine(adjacent);
            if (line == null) continue;
            if (config.matchesLeft(line)) {
                // 验证对面是右侧关键字
                Block rightBlock = block.getRelative(face.getOppositeFace());
                if (isWoodenSign(rightBlock)) {
                    String rightLine = getSignFirstLine(rightBlock);
                    if (config.matchesRight(rightLine)) {
                        return face;
                    }
                }
            }
        }
        return null;
    }

    /**
     * 获取告示牌第一行文本
     */
    private String getSignFirstLine(Block block) {
        if (block.getState() instanceof Sign sign) {
            // 告示牌可双面书写，取正面（FRONT）第 0 行；Sign#line(int) 已过时
            return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
                    .plainText().serialize(sign.getSide(org.bukkit.block.sign.Side.FRONT).line(0));
        }
        return null;
    }

    /**
     * 判断方块是否为配置允许的木质告示牌（含墙上告示牌与悬挂告示牌）
     */
    private boolean isWoodenSign(Block block) {
        Material mat = block.getType();
        String name = mat.name();
        if (!name.endsWith("_SIGN") && !name.endsWith("_WALL_SIGN")
                && !name.endsWith("_HANGING_SIGN") && !name.endsWith("_WALL_HANGING_SIGN")) {
            return false;
        }
        List<String> woods = config.getAllowedWoods();
        for (String wood : woods) {
            if (name.startsWith(wood.toUpperCase() + "_")) return true;
        }
        return false;
    }

    /**
     * 根据左侧告示牌的朝向计算玩家朝向
     * 玩家面朝某方向时，左手边的方向：
     * - 朝北(NORTH): 左=西(WEST)
     * - 朝南(SOUTH): 左=东(EAST)
     * - 朝东(EAST):  左=北(NORTH)
     * - 朝西(WEST):  左=南(SOUTH)
     */
    private BlockFace getFacingFromLeft(BlockFace leftFace) {
        return switch (leftFace) {
            case WEST -> BlockFace.NORTH;
            case EAST -> BlockFace.SOUTH;
            case NORTH -> BlockFace.EAST;
            case SOUTH -> BlockFace.WEST;
            default -> null;
        };
    }

    /**
     * 将 BlockFace 转为 yaw 角度
     */
    private float getYawFromFacing(BlockFace face) {
        if (face == null) return 0f;
        return switch (face) {
            case NORTH -> 180f;
            case SOUTH -> 0f;
            case EAST -> -90f;
            case WEST -> 90f;
            default -> 0f;
        };
    }
}
