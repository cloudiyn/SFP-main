package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.util.DatabaseFileResolver;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.config.module.BotConfig;
import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 假人管理（{@code /sfp player} / {@code /sfp fp}）。
 * <p>
 * 假人是通过 {@link NmsBotFactory} 创建的真玩家实体（NMS ServerPlayer），
 * 因此它天然保持周围区块加载 —— 这就是「挂机假人」保区块的原理。
 * <p>
 * 假人没有会话，**服务器重启后必须重新加入**：所以这里把每个假人记在
 * 数据目录的 {@code bots.yml}（名字 / UUID / 创建者 / 位置 / 设置 / 持续动作），
 * 启动时按记录重建。
 * <p>
 * 名字即身份：UUID 由名字派生（{@code StarfallBot:名字}），所以同一个名字永远是同一个档案，
 * 重名会被拒绝。
 * <p>
 * 本类负责：生命周期（创建 / 移除 / 重建 / 记录）、选择（select）、设置（set / config）
 * 与纯 Bukkit 层操作（背包 / 传送 / 经验 / 丢物品 / 皮肤 / 命令）。
 * 需要碰 NMS 的动作（攻击 / 挖掘 / 使用 / 移动 / 睡觉 / 骑乘 / 复活……）
 * 与周期任务统一在 {@link BotActions}。
 */
public class BotManager {

    /** 名字规则：Minecraft 玩家名限制 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** 一个假人的记录（含运行时可变的设置与持续动作） */
    static final class Bot {
        final String name;
        final UUID uuid;
        final UUID owner;
        String world;
        double x;
        double y;
        double z;
        float yaw;
        float pitch;
        final Map<BotSettings, Boolean> settings = new EnumMap<>(BotSettings.class);
        final EnumSet<BotAction> actions = EnumSet.noneOf(BotAction.class);

        Bot(String name, UUID uuid, UUID owner, String world,
            double x, double y, double z, float yaw, float pitch) {
            this.name = name;
            this.uuid = uuid;
            this.owner = owner;
            this.world = world;
            this.x = x;
            this.y = y;
            this.z = z;
            this.yaw = yaw;
            this.pitch = pitch;
        }

        Location location(World w) {
            return new Location(w, x, y, z, yaw, pitch);
        }
    }

    private final SfpMain plugin;
    private final BotConfig config;
    private final File dataFile;
    /** 小写名字 → 记录 */
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    /** 执行者 UUID → 其选定的假人名字（小写） */
    private final Map<UUID, String> selection = new LinkedHashMap<>();
    /** 玩家 UUID → 个人默认配置（创建假人时套用） */
    private final Map<UUID, Map<BotSettings, Boolean>> playerConfigs = new LinkedHashMap<>();
    /** 持续动作 / 设置驱动的周期任务 */
    private final BotActions actions;
    /** 实时背包编辑器（仅主线程访问） */
    private final BotInventoryManager inventories;
    /** 定期清理假连接积压数据包的任务 */
    private BukkitTask drainTask;

    public BotManager(SfpMain plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = configManager.bot();
        this.dataFile = DatabaseFileResolver.resolveDataFile(plugin, "bots.yml");
        this.actions = new BotActions(plugin, this, config);
        this.inventories = new BotInventoryManager(plugin, this);
        loadData();
        startDrainTask();
    }

    // ==================== 任务 ====================

    /**
     * 定期清空假人连接上积压的数据包（每 60 秒一次）。
     * <p>
     * 假人的「网络连接」是一个被当作吞包黑洞的 EmbeddedChannel，服务端发给它的包
     * 没人消费，不清就会一直堆积。
     */
    private void startDrainTask() {
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Bot bot : bots.values()) {
                Player online = Bukkit.getPlayer(bot.uuid);
                if (online != null) {
                    NmsBotFactory.drainPendingPackets(online);
                }
            }
        }, 1200L, 1200L);
    }

    /** 启动持续动作周期任务（由主类在假人重建前后调用） */
    public void startActions() {
        actions.start();
    }

    /** 插件卸载：停掉周期任务（假人实体本身随服务端关闭而消失） */
    public void shutdown() {
        inventories.shutdown();
        actions.shutdown();
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
    }

    public BotConfig getConfig() {
        return config;
    }

    /** 动作执行器（一次性动作也走它） */
    public BotActions actions() {
        return actions;
    }

    // ==================== 查询 ====================

    /** 假人记录文件（data/bots.yml，供自检读取） */
    public File dataFile() {
        return dataFile;
    }

    /** 当前记录的假人数量（供 /sfp status 与自检显示运行态） */
    public int size() {
        return bots.size();
    }

    /** 其中已成功加入服务端（在线）的数量 */
    public int onlineCount() {
        int online = 0;
        for (Bot bot : bots.values()) {
            if (Bukkit.getPlayer(bot.uuid) != null) online++;
        }
        return online;
    }

    /** 其中带持续动作（attack/mine/use/move）的假人数量 */
    public int activeActionCount() {
        int count = 0;
        for (Bot bot : bots.values()) {
            if (!bot.actions.isEmpty()) count++;
        }
        return count;
    }

    /** 假人名字列表（供命令补全） */
    public List<String> names() {
        return bots.values().stream().map(b -> b.name).toList();
    }

    /** 全部记录（只读视图） */
    public List<Bot> all() {
        return List.copyOf(bots.values());
    }

    /** 该 UUID 是否为本插件的假人 */
    public boolean isBot(UUID uuid) {
        for (Bot bot : bots.values()) {
            if (bot.uuid.equals(uuid)) return true;
        }
        return false;
    }

    /** 是否存在这个名字的假人（大小写不敏感） */
    public boolean hasName(String name) {
        return name != null && bots.containsKey(name.toLowerCase());
    }

    /** 按名字取记录（大小写不敏感）；不存在返回 null */
    public Bot byName(String name) {
        return name == null ? null : bots.get(name.toLowerCase());
    }

    /** 按 UUID 找记录 */
    public Bot byUuid(UUID uuid) {
        for (Bot bot : bots.values()) {
            if (bot.uuid.equals(uuid)) return bot;
        }
        return null;
    }

    /** 取在线假人实体；不在线返回 null */
    public Player online(Bot bot) {
        return Bukkit.getPlayer(bot.uuid);
    }

    // ==================== 启动重建 ====================

    /**
     * 按 bots.yml 的记录重建假人。
     * <p>
     * 必须在世界加载完成之后调用（Paper 插件的 POSTWORLD 阶段已经满足）。
     * 单个假人重建失败只记日志，不影响其他假人与插件启动。
     */
    public void restoreOnStart() {
        if (!config.isRestoreOnStart() || bots.isEmpty()) return;

        int ok = 0;
        for (Bot bot : new ArrayList<>(bots.values())) {
            World world = Bukkit.getWorld(bot.world);
            if (world == null) {
                plugin.getLogger().warning("假人 " + bot.name + " 重建失败：世界 " + bot.world
                        + " 不存在（记录保留，世界恢复后下次启动会再试）。");
                continue;
            }
            if (Bukkit.getPlayer(bot.uuid) != null) {
                ok++;
                continue;
            }
            try {
                NmsBotFactory.spawn(plugin, resolveSkinSource(null, bot.settings),
                        world, bot.location(world), bot.name, bot.uuid);
                applySettings(bot);
                ok++;
            } catch (Throwable t) {
                plugin.getLogger().warning("假人 " + bot.name + " 重建失败：" + t);
            }
        }
        plugin.getLogger().info("假人：已重建 " + ok + "/" + bots.size() + " 个。");
    }

    // ==================== 创建 / 删除 ====================

    /** 创建假人；成功返回 true。所有提示都直接发给 sender。 */
    public boolean create(CommandSender sender, String rawName, World world, Location loc) {
        String name = rawName == null ? "" : rawName.trim();

        if (!NAME_PATTERN.matcher(name).matches() || name.length() > config.getMaxNameLength()) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("max", String.valueOf(config.getMaxNameLength()));
            messages().send(sender, "bot.invalid-name",
                    "<red>假人名字只能包含字母、数字、下划线，长度 1~{max}。</red>", ph);
            return false;
        }
        if (bots.containsKey(name.toLowerCase())) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", name);
            messages().send(sender, "bot.exists",
                    "<red>已存在名为「{name}」的假人。</red>", ph);
            return false;
        }

        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        int max = config.getMaxPerPlayer();
        if (max > 0 && owner != null) {
            long mine = bots.values().stream().filter(b -> owner.equals(b.owner)).count();
            if (mine >= max) {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("max", String.valueOf(max));
                messages().send(sender, "bot.limit-reached",
                        "<red>你创建的假人已达上限（{max} 个）。</red>", ph);
                return false;
            }
        }

        UUID uuid = deriveUuid(name);
        Player creator = sender instanceof Player p ? p : null;
        try {
            NmsBotFactory.spawn(plugin, resolveSkinSource(creator, playerConfigs.get(owner)),
                    world, loc, name, uuid);
        } catch (Throwable t) {
            plugin.getLogger().warning("创建假人 " + name + " 失败：" + t);
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("error", String.valueOf(t));
            messages().send(sender, "bot.create-failed",
                    "<red>创建假人失败：{error}</red>", ph);
            return false;
        }

        Bot bot = new Bot(name, uuid, owner, world.getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        // 设置 = 个人默认配置 覆盖 全局默认
        Map<BotSettings, Boolean> defaults = config.getDefaults();
        for (BotSettings s : BotSettings.values()) {
            bot.settings.put(s, defaults.getOrDefault(s, true));
        }
        if (owner != null) {
            Map<BotSettings, Boolean> personal = playerConfigs.get(owner);
            if (personal != null) bot.settings.putAll(personal);
        }
        bots.put(name.toLowerCase(), bot);
        applySettings(bot);
        saveData();

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", name);
        ph.put("location", world.getName() + " " + Math.round(loc.getX()) + ", "
                + Math.round(loc.getY()) + ", " + Math.round(loc.getZ()));
        messages().send(sender, "bot.created",
                "<green>已创建假人「{name}」（{location}），它会保持所在区块加载。</green>", ph);
        return true;
    }

    /** 删除假人 */
    public boolean remove(CommandSender sender, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        Bot bot = bots.remove(name.toLowerCase());
        if (bot == null) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", name);
            messages().send(sender, "bot.not-found",
                    "<red>不存在名为「{name}」的假人。</red>", ph);
            return false;
        }
        Player online = online(bot);
        if (online != null) {
            NmsBotFactory.remove(plugin, online);
        }
        actions.onBotRemoved(bot.uuid);
        dropSelection(bot.uuid);
        saveData();

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.removed",
                "<green>已移除假人「{name}」。</green>", ph);
        return true;
    }

    /** 删除全部假人 */
    public void removeAll(CommandSender sender) {
        int count = bots.size();
        for (Bot bot : new ArrayList<>(bots.values())) {
            Player online = online(bot);
            if (online != null) {
                NmsBotFactory.remove(plugin, online);
            }
            actions.onBotRemoved(bot.uuid);
            dropSelection(bot.uuid);
        }
        bots.clear();
        saveData();

        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("count", String.valueOf(count));
        messages().send(sender, "bot.removed-all",
                "<green>已移除全部假人（{count} 个）。</green>", ph);
    }

    /** 列出全部假人 */
    public void list(CommandSender sender) {
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("count", String.valueOf(bots.size()));
        messages().send(sender, "bot.list-header",
                "<aqua>假人（{count}）：</aqua>", ph);

        if (bots.isEmpty()) {
            messages().send(sender, "bot.list-empty",
                    "<yellow>当前没有假人。</yellow>", Map.of());
            return;
        }
        for (Bot bot : bots.values()) {
            Player online = online(bot);
            Location loc = online != null ? online.getLocation() : null;
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("name", bot.name);
            entry.put("world", online != null ? online.getWorld().getName() : bot.world);
            entry.put("x", String.valueOf(Math.round(online != null ? loc.getX() : bot.x)));
            entry.put("y", String.valueOf(Math.round(online != null ? loc.getY() : bot.y)));
            entry.put("z", String.valueOf(Math.round(online != null ? loc.getZ() : bot.z)));
            entry.put("state", online == null ? "未加入" : (online.isDead() ? "已死亡" : "在线"));
            entry.put("actions", actions.describe(bot));
            messages().send(sender, "bot.list-entry",
                    "<gray> - </gray><white>{name}</white> <dark_gray>({world} {x}, {y}, {z}) {state}{actions}</dark_gray>",
                    entry);
        }
    }

    // ==================== 选择 ====================

    /** 设定执行者的默认假人 */
    public boolean select(CommandSender sender, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        Bot bot = bots.get(name.toLowerCase());
        if (bot == null) {
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", name);
            messages().send(sender, "bot.not-found",
                    "<red>不存在名为「{name}」的假人。</red>", ph);
            return false;
        }
        if (sender instanceof Player player) {
            selection.put(player.getUniqueId(), bot.name.toLowerCase());
            Map<String, String> ph = new LinkedHashMap<>();
            ph.put("name", bot.name);
            messages().send(sender, "bot.selected",
                    "<green>已把「{name}」设为默认假人，之后的命令可以省略名字。</green>", ph);
        } else {
            messages().send(sender, "bot.select-player-only",
                    "<red>只有玩家可以设定默认假人（控制台请每次显式写名字）。</red>", Map.of());
        }
        return true;
    }

    /** 查看执行者当前选定的假人 */
    public void selection(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.selection-none",
                    "<gray>当前没有选定的假人。</gray>", Map.of());
            return;
        }
        String name = selection.get(player.getUniqueId());
        if (name == null) {
            messages().send(sender, "bot.selection-none",
                    "<gray>当前没有选定的假人。</gray>", Map.of());
            return;
        }
        Bot bot = bots.get(name);
        if (bot == null) {
            messages().send(sender, "bot.selection-none",
                    "<gray>当前没有选定的假人。</gray>", Map.of());
            return;
        }
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.selection-current",
                "<gray>当前默认假人：<white>{name}</white></gray>", ph);
    }

    /** 执行者退出：清掉其选择 */
    public void clearSelection(UUID executor) {
        selection.remove(executor);
    }

    /** 假人离开服务端（被踢 / 死亡移除）：清掉指向它的选择 */
    public void dropSelection(UUID botUuid) {
        selection.values().removeIf(n -> {
            Bot bot = bots.get(n);
            return bot == null || bot.uuid.equals(botUuid);
        });
    }

    /**
     * 解析命令里的假人参数：显式名字优先，否则用执行者的选择。
     * <p>
     * 解析失败时直接把错误提示发给 sender 并返回 null。
     *
     * @param nameArg 命令里给的第一个参数（可能是假人名，也可能是别的参数）
     */
    public Bot resolve(CommandSender sender, String nameArg) {
        Bot bot = null;
        if (nameArg != null && !nameArg.isBlank()) {
            bot = bots.get(nameArg.trim().toLowerCase());
            if (bot == null) {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("name", nameArg.trim());
                messages().send(sender, "bot.not-found",
                        "<red>不存在名为「{name}」的假人。</red>", ph);
                return null;
            }
            return bot;
        }
        if (sender instanceof Player player) {
            String selected = selection.get(player.getUniqueId());
            if (selected != null) bot = bots.get(selected);
        }
        if (bot == null) {
            messages().send(sender, "bot.no-selection",
                    "<red>请指定假人名，或先用 <white>/sfp player select <名字></white> 设定默认假人。</red>",
                    Map.of());
            return null;
        }
        return bot;
    }

    // ==================== 信息类 ====================

    /** 假人详细状态 */
    public void status(CommandSender sender, Bot bot) {
        Player online = online(bot);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("state", online == null ? "未加入"
                : (online.isDead() ? "已死亡" : "在线"));
        messages().send(sender, "bot.status-header",
                "<aqua>假人「{name}」[{state}]</aqua>", ph);
        if (online == null) {
            ph.put("world", bot.world);
            ph.put("x", String.valueOf(Math.round(bot.x)));
            ph.put("y", String.valueOf(Math.round(bot.y)));
            ph.put("z", String.valueOf(Math.round(bot.z)));
            messages().send(sender, "bot.status-offline",
                    "<gray>记录位置：{world} {x}, {y}, {z}（重启后按此位置重建）</gray>", ph);
        } else {
            Location loc = online.getLocation();
            ph.put("world", loc.getWorld().getName());
            ph.put("x", String.valueOf(Math.round(loc.getX())));
            ph.put("y", String.valueOf(Math.round(loc.getY())));
            ph.put("z", String.valueOf(Math.round(loc.getZ())));
            ph.put("health", String.valueOf(Math.round(online.getHealth() * 10.0) / 10.0));
            ph.put("food", String.valueOf(online.getFoodLevel()));
            ph.put("gamemode", online.getGameMode().name().toLowerCase());
            messages().send(sender, "bot.status-online",
                    "<gray>位置：{world} {x}, {y}, {z}　生命：{health}　饱食度：{food}　模式：{gamemode}</gray>",
                    ph);
        }
        ph.put("actions", actions.describe(bot));
        messages().send(sender, "bot.status-actions",
                "<gray>持续动作：{actions}</gray>", ph);
        messages().send(sender, "bot.status-settings-header",
                "<gray>设置：</gray>", Map.of());
        for (BotSettings s : BotSettings.values()) {
            Map<String, String> e = new LinkedHashMap<>();
            e.put("key", s.key());
            e.put("display", s.display());
            e.put("value", BotSettings.format(bot.settings.get(s)));
            messages().send(sender, "bot.status-settings-entry",
                    "<dark_gray>  · </dark_gray><white>{display}</white><gray>（{key}）：{value}</gray>", e);
        }
        String ownerText = bot.owner == null ? "控制台"
                : String.valueOf(Bukkit.getOfflinePlayer(bot.owner).getName());
        ph.put("owner", ownerText);
        messages().send(sender, "bot.status-owner",
                "<gray>创建者：{owner}</gray>", ph);
    }

    /** 与假人的距离 */
    public void distance(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.player-only",
                    "<red>该操作只能由玩家执行。</red>", Map.of());
            return;
        }
        if (!player.getWorld().equals(online.getWorld())) {
            messages().send(sender, "bot.distance-other-world",
                    "<yellow>假人「{name}」在另一个世界（{world}）。</yellow>",
                    Map.of("name", bot.name, "world", online.getWorld().getName()));
            return;
        }
        double d = player.getLocation().distance(online.getLocation());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("distance", String.valueOf(Math.round(d * 10.0) / 10.0));
        messages().send(sender, "bot.distance",
                "<gray>与假人「{name}」的距离：<white>{distance}</white> 格</gray>", ph);
    }

    // ==================== 背包 / 物品 ====================

    /** 打开假人背包（右键假人同效） */
    public boolean invsee(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        if (!(sender instanceof Player viewer)) {
            messages().send(sender, "bot.player-only",
                    "<red>该操作只能由玩家执行。</red>", Map.of());
            return false;
        }
        if (!viewer.hasPermission("sfpmenu.bot")) {
            messages().send(viewer, "common.no-permission", "", Map.of());
            return false;
        }
        inventories.open(viewer, bot);
        return true;
    }

    /** 背包会话生命周期入口（由 BotListener 转交）。 */
    public BotInventoryManager inventories() {
        return inventories;
    }

    /** 丢弃手持物品（1 个） */
    public boolean drop(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        PlayerInventory inv = online.getInventory();
        ItemStack held = inv.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            messages().send(sender, "bot.drop-empty",
                    "<yellow>假人「{name}」手上没有物品。</yellow>", Map.of("name", bot.name));
            return false;
        }
        ItemStack one = held.clone();
        one.setAmount(1);
        online.getWorld().dropItemNaturally(online.getLocation(), one);
        int remaining = held.getAmount() - 1;
        if (remaining <= 0) inv.setItemInMainHand(null);
        else held.setAmount(remaining);
        messages().send(sender, "bot.dropped",
                "<green>假人「{name}」丢出了 1 个物品。</green>", Map.of("name", bot.name));
        return true;
    }

    /** 丢弃整叠手持物品 */
    public boolean dropStack(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        PlayerInventory inv = online.getInventory();
        ItemStack held = inv.getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            messages().send(sender, "bot.drop-empty",
                    "<yellow>假人「{name}」手上没有物品。</yellow>", Map.of("name", bot.name));
            return false;
        }
        online.getWorld().dropItemNaturally(online.getLocation(), held.clone());
        inv.setItemInMainHand(null);
        messages().send(sender, "bot.dropped-stack",
                "<green>假人「{name}」丢出了整叠物品。</green>", Map.of("name", bot.name));
        return true;
    }

    /** 丢弃整个背包（储物格 + 副手） */
    public boolean dropInventory(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        PlayerInventory inv = online.getInventory();
        int dropped = 0;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inv.getItem(slot);
            if (item == null || item.getType().isAir()) continue;
            online.getWorld().dropItemNaturally(online.getLocation(), item.clone());
            inv.setItem(slot, null);
            dropped += item.getAmount();
        }
        ItemStack off = inv.getItemInOffHand();
        if (off != null && !off.getType().isAir()) {
            online.getWorld().dropItemNaturally(online.getLocation(), off.clone());
            inv.setItemInOffHand(null);
            dropped += off.getAmount();
        }
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("count", String.valueOf(dropped));
        messages().send(sender, "bot.dropped-inv",
                "<green>假人「{name}」丢出了整个背包（{count} 个物品）。</green>", ph);
        return true;
    }

    /** 主副手互换 */
    public boolean swap(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        PlayerInventory inv = online.getInventory();
        ItemStack main = inv.getItemInMainHand();
        ItemStack off = inv.getItemInOffHand();
        inv.setItemInMainHand(off);
        inv.setItemInOffHand(main);
        messages().send(sender, "bot.swapped",
                "<green>假人「{name}」已互换主副手物品。</green>", Map.of("name", bot.name));
        return true;
    }

    /** 切换快捷栏槽位（1~9） */
    public boolean hold(CommandSender sender, Bot bot, int slot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        online.getInventory().setHeldItemSlot(slot - 1);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("slot", String.valueOf(slot));
        messages().send(sender, "bot.held",
                "<green>假人「{name}」已切到快捷栏第 {slot} 格。</green>", ph);
        return true;
    }

    // ==================== 传送 / 经验 / 命令 ====================

    /** 传送到假人处 */
    public boolean teleportTo(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.player-only",
                    "<red>该操作只能由玩家执行。</red>", Map.of());
            return false;
        }
        player.teleport(online.getLocation());
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.teleported-to",
                "<green>已传送到假人「{name}」身边。</green>", ph);
        return true;
    }

    /** 把假人传到自己身边 */
    public boolean teleportHere(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.player-only",
                    "<red>该操作只能由玩家执行。</red>", Map.of());
            return false;
        }
        online.teleport(player.getLocation());
        NmsBotFactory.syncOnGround(plugin, online);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.teleported-here",
                "<green>已把假人「{name}」传到身边。</green>", ph);
        return true;
    }

    /** 与假人交换位置 */
    public boolean teleportSwap(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.player-only",
                    "<red>该操作只能由玩家执行。</red>", Map.of());
            return false;
        }
        Location from = player.getLocation().clone();
        Location to = online.getLocation().clone();
        player.teleport(to);
        online.teleport(from);
        NmsBotFactory.syncOnGround(plugin, online);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.teleported-swap",
                "<green>已与假人「{name}」交换位置。</green>", ph);
        return true;
    }

    /** 把假人的经验转给执行者 */
    public boolean expMe(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        int total = online.getTotalExperience();
        if (total <= 0) {
            messages().send(sender, "bot.exp-none",
                    "<yellow>假人「{name}」没有经验可转移。</yellow>", Map.of("name", bot.name));
            return false;
        }
        online.setTotalExperience(0);
        online.setExp(0f);
        online.setLevel(0);
        if (sender instanceof Player player) {
            player.giveExp(total);
        }
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("amount", String.valueOf(total));
        messages().send(sender, "bot.exp-transferred",
                "<green>已把假人「{name}」的 {amount} 点经验转给你。</green>", ph);
        return true;
    }

    /** 以假人身份执行命令 */
    public boolean command(CommandSender sender, Bot bot, String commandLine) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        if (commandLine == null || commandLine.isBlank()) {
            messages().send(sender, "bot.cmd-empty",
                    "<red>要执行的命令不能为空。</red>", Map.of());
            return false;
        }
        boolean ok = online.performCommand(commandLine);
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("command", commandLine);
        if (ok) {
            messages().send(sender, "bot.cmd-done",
                    "<green>已让假人「{name}」执行命令：/{command}</green>", ph);
        } else {
            messages().send(sender, "bot.cmd-failed",
                    "<red>假人「{name}」执行命令失败（可能没有权限或命令不存在）：/{command}</red>", ph);
        }
        return ok;
    }

    // ==================== 皮肤 ====================

    /**
     * 复制其他玩家的皮肤给假人。
     * <p>
     * 在线玩家直接取其档案；离线玩家走 {@code complete()}（需要能访问 Mojang 服务器，
     * 即正版验证开启或玩家曾在线）。
     * 假人自己的 UUID / 名字不变，只换 textures 属性，因此背包等数据不受影响。
     */
    public boolean skin(CommandSender sender, Bot bot, String sourceName) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        PlayerProfile source;
        Player src = Bukkit.getPlayerExact(sourceName);
        if (src != null) {
            source = src.getPlayerProfile();
        } else {
            // createPlayerProfile 返回 Bukkit 接口，实际对象是 Paper 的实现（可安全下转）
            source = (PlayerProfile) Bukkit.createPlayerProfile(sourceName);
            if (!source.complete(true)) {
                Map<String, String> ph = new LinkedHashMap<>();
                ph.put("name", sourceName);
                messages().send(sender, "bot.skin-not-found",
                        "<red>找不到玩家「{name}」的皮肤（离线玩家需要服务器能访问 Mojang 皮肤服务器）。</red>",
                        ph);
                return false;
            }
        }
        try {
            PlayerProfile target = (PlayerProfile) Bukkit.createPlayerProfile(bot.uuid, bot.name);
            for (com.destroystokyo.paper.profile.ProfileProperty prop : source.getProperties()) {
                if ("textures".equals(prop.getName())) {
                    target.setProperty(prop);
                }
            }
            online.setPlayerProfile(target);
        } catch (Throwable t) {
            plugin.getLogger().warning("设置假人 " + bot.name + " 皮肤失败：" + t);
            messages().send(sender, "bot.skin-failed",
                    "<red>设置皮肤失败：{error}</red>",
                    Map.of("error", String.valueOf(t)));
            return false;
        }
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("source", sourceName);
        messages().send(sender, "bot.skin-copied",
                "<green>假人「{name}」已套用「{source}」的皮肤。</green>", ph);
        return true;
    }

    // ==================== 设置 ====================

    /** 把记录里的设置应用到在线实体上（package-private：复活后由 BotActions 重放） */
    void applySettings(Bot bot) {
        Player online = online(bot);
        if (online == null) return;
        online.setInvulnerable(bot.settings.getOrDefault(BotSettings.INVULNERABLE, true));
        online.setCollidable(bot.settings.getOrDefault(BotSettings.COLLIDABLE, true));
        online.setCanPickupItems(bot.settings.getOrDefault(BotSettings.PICKUP_ITEMS, true));
        // 假人没有客户端移动包，NMS 的 onGround 永远是 false（横扫/暴击判定失效），
        // 每次重放设置（创建 / 重启重建 / 复活 / 传送）时按脚下方块校正一次。
        NmsBotFactory.syncOnGround(plugin, online);
    }

    /** 修改单个假人的设置 */
    public boolean setSetting(CommandSender sender, Bot bot, String key, String value) {
        BotSettings setting = BotSettings.byKey(key);
        Boolean parsed = BotSettings.parseBoolean(value);
        if (setting == null || parsed == null) {
            messages().send(sender, "bot.set-usage",
                    "<red>用法：/sfp player set [名字] <键> <true|false>，可用键：collidable / invulnerable / "
                            + "wolverine / look_at_entity / pickup_items / replenish / autofish / skin</red>",
                    Map.of());
            return false;
        }
        bot.settings.put(setting, parsed);
        applySettings(bot);
        saveData();
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("display", setting.display());
        ph.put("value", BotSettings.format(parsed));
        messages().send(sender, "bot.set-done",
                "<green>假人「{name}」的「{display}」已设为 {value}。</green>", ph);
        return true;
    }

    /** 查看执行者的个人默认配置 */
    public void configList(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.config-player-only",
                    "<red>个人配置按玩家保存，控制台无法使用（控制台创建的假人用全局默认设置）。</red>",
                    Map.of());
            return;
        }
        Map<BotSettings, Boolean> personal = playerConfigs.get(player.getUniqueId());
        messages().send(sender, "bot.config-header",
                "<aqua>你的假人默认配置（创建新假人时套用）：</aqua>", Map.of());
        for (BotSettings s : BotSettings.values()) {
            Boolean value = personal != null && personal.containsKey(s)
                    ? personal.get(s)
                    : config.getDefaults().getOrDefault(s, true);
            Map<String, String> e = new LinkedHashMap<>();
            e.put("key", s.key());
            e.put("display", s.display());
            e.put("value", BotSettings.format(value));
            e.put("origin", personal != null && personal.containsKey(s) ? "个人" : "全局默认");
            messages().send(sender, "bot.config-entry",
                    "<dark_gray>  · </dark_gray><white>{display}</white><gray>（{key}）：{value}<dark_gray>（{origin}）</dark_gray></gray>",
                    e);
        }
    }

    /** 设置执行者的个人默认配置 */
    public boolean configSet(CommandSender sender, String key, String value) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.config-player-only",
                    "<red>个人配置按玩家保存，控制台无法使用（控制台创建的假人用全局默认设置）。</red>",
                    Map.of());
            return false;
        }
        BotSettings setting = BotSettings.byKey(key);
        Boolean parsed = BotSettings.parseBoolean(value);
        if (setting == null || parsed == null) {
            messages().send(sender, "bot.config-set-usage",
                    "<red>用法：/sfp player config set <键> <true|false></red>",
                    Map.of());
            return false;
        }
        playerConfigs.computeIfAbsent(player.getUniqueId(), k -> new EnumMap<>(BotSettings.class))
                .put(setting, parsed);
        saveData();
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("display", setting.display());
        ph.put("value", BotSettings.format(parsed));
        messages().send(sender, "bot.config-set-done",
                "<green>你的默认配置「{display}」已设为 {value}（下次创建假人时生效）。</green>", ph);
        return true;
    }

    /** 清空执行者的个人默认配置（恢复全局默认） */
    public boolean configClear(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            messages().send(sender, "bot.config-player-only",
                    "<red>个人配置按玩家保存，控制台无法使用（控制台创建的假人用全局默认设置）。</red>",
                    Map.of());
            return false;
        }
        if (playerConfigs.remove(player.getUniqueId()) == null) {
            messages().send(sender, "bot.config-clear-none",
                    "<yellow>你还没有个人配置。</yellow>", Map.of());
            return false;
        }
        saveData();
        messages().send(sender, "bot.config-cleared",
                "<green>已清空你的个人配置（恢复全局默认）。</green>", Map.of());
        return true;
    }

    // ==================== 持续动作 ====================

    /** 切换持续动作（attack / mine / use / move） */
    public boolean toggleAction(CommandSender sender, Bot bot, BotAction action) {
        Player online = online(bot);
        if (online == null) {
            messages().send(sender, "bot.offline",
                    "<red>假人「{name}」当前不在线。</red>", Map.of("name", bot.name));
            return false;
        }
        boolean on = !bot.actions.contains(action);
        if (on) {
            bot.actions.add(action);
        } else {
            bot.actions.remove(action);
        }
        actions.onActionChanged(online, bot, action, on);
        saveData();
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        ph.put("display", action.display());
        ph.put("state", on ? "开始" : "停止");
        messages().send(sender, "bot.action-toggled",
                "<green>假人「{name}」{state}{display}。</green>", ph);
        return true;
    }

    /** 停止全部持续动作 */
    public boolean stopActions(CommandSender sender, Bot bot) {
        Player online = online(bot);
        if (bot.actions.isEmpty()) {
            messages().send(sender, "bot.action-none",
                    "<gray>假人「{name}」当前没有持续动作。</gray>", Map.of("name", bot.name));
            return false;
        }
        bot.actions.clear();
        if (online != null) {
            actions.onActionChanged(online, bot, null, false);
        }
        saveData();
        Map<String, String> ph = new LinkedHashMap<>();
        ph.put("name", bot.name);
        messages().send(sender, "bot.action-stopped",
                "<green>假人「{name}」已停止全部持续动作。</green>", ph);
        return true;
    }

    // ==================== 皮肤来源 ====================

    /**
     * 皮肤来源：
     * <ul>
     *   <li>配置 {@code skin-source} 非空 —— 按配置（{@code none} = 默认皮肤，否则取该在线玩家）</li>
     *   <li>配置留空 —— 看假人设置的 {@code skin} 项：true 用创建者皮肤，false 用默认皮肤</li>
     * </ul>
     */
    private Player resolveSkinSource(Player creator, Map<BotSettings, Boolean> settings) {
        String source = config.getSkinSource();
        if (source != null && !source.isBlank()) {
            if (source.equalsIgnoreCase("none")) return null;
            return Bukkit.getPlayerExact(source);
        }
        boolean useCreator = settings == null
                || settings.getOrDefault(BotSettings.SKIN, true);
        return useCreator ? creator : null;
    }

    private Messages messages() {
        return plugin.getConfigManager().messages();
    }

    /** 名字 → 稳定 UUID（同一个名字永远同一个档案） */
    private static UUID deriveUuid(String name) {
        return UUID.nameUUIDFromBytes(("StarfallBot:" + name.toLowerCase()).getBytes(StandardCharsets.UTF_8));
    }

    // ==================== 数据文件 ====================

    private void loadData() {
        if (!dataFile.exists()) return;
        YamlConfiguration yml = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection section = yml.getConfigurationSection("bots");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                String path = "bots." + key + ".";
                try {
                    String uuidRaw = yml.getString(path + "uuid", "");
                    String ownerRaw = yml.getString(path + "owner");
                    Bot bot = new Bot(
                            yml.getString(path + "name", key),
                            UUID.fromString(uuidRaw),
                            ownerRaw == null || ownerRaw.isBlank() ? null : UUID.fromString(ownerRaw),
                            yml.getString(path + "world", "world"),
                            yml.getDouble(path + "x"), yml.getDouble(path + "y"), yml.getDouble(path + "z"),
                            (float) yml.getDouble(path + "yaw"), (float) yml.getDouble(path + "pitch"));
                    loadSettings(yml.getConfigurationSection(path + "settings"), bot.settings);
                    loadActions(yml.getStringList(path + "actions"), bot.actions);
                    bots.put(key.toLowerCase(), bot);
                } catch (Exception e) {
                    plugin.getLogger().warning("读取假人记录失败（" + key + "）：" + e.getMessage());
                }
            }
        }
        ConfigurationSection configs = yml.getConfigurationSection("player-configs");
        if (configs != null) {
            for (String key : configs.getKeys(false)) {
                try {
                    UUID uuid = UUID.fromString(key);
                    Map<BotSettings, Boolean> settings = new EnumMap<>(BotSettings.class);
                    loadSettings(configs.getConfigurationSection(key), settings);
                    if (!settings.isEmpty()) playerConfigs.put(uuid, settings);
                } catch (Exception e) {
                    plugin.getLogger().warning("读取假人个人配置失败（" + key + "）：" + e.getMessage());
                }
            }
        }
    }

    private void loadSettings(ConfigurationSection section, Map<BotSettings, Boolean> target) {
        if (section == null) return;
        for (String key : section.getKeys(false)) {
            BotSettings setting = BotSettings.byKey(key);
            if (setting != null) {
                target.put(setting, section.getBoolean(key));
            }
        }
    }

    private void loadActions(List<String> raw, EnumSet<BotAction> target) {
        if (raw == null) return;
        for (String name : raw) {
            BotAction action = BotAction.byKey(name);
            if (action != null) target.add(action);
        }
    }

    /** 保存记录（创建 / 删除 / 改设置后都会调用；在线假人的位置同步刷新） */
    public void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(java.util.List.of(
                "假人记录：服务器重启后插件会按这里的内容重建假人。",
                "一般不需要手动编辑；直接删掉某个条目也能阻止它下次被重建。",
                "settings = 假人设置，actions = 持续动作（重启后继续执行）。",
                "player-configs = 每位玩家的默认配置（创建假人时套用）。"));
        for (Bot bot : bots.values()) {
            // 在线假人刷新一次位置，重启后回到最后所在处
            Player online = online(bot);
            if (online != null && !online.isDead()) {
                Location loc = online.getLocation();
                bot.world = loc.getWorld().getName();
                bot.x = loc.getX();
                bot.y = loc.getY();
                bot.z = loc.getZ();
                bot.yaw = loc.getYaw();
                bot.pitch = loc.getPitch();
            }
            String path = "bots." + bot.name.toLowerCase() + ".";
            yml.set(path + "name", bot.name);
            yml.set(path + "uuid", bot.uuid.toString());
            yml.set(path + "owner", bot.owner == null ? "" : bot.owner.toString());
            yml.set(path + "world", bot.world);
            yml.set(path + "x", bot.x);
            yml.set(path + "y", bot.y);
            yml.set(path + "z", bot.z);
            yml.set(path + "yaw", bot.yaw);
            yml.set(path + "pitch", bot.pitch);
            for (Map.Entry<BotSettings, Boolean> e : bot.settings.entrySet()) {
                yml.set(path + "settings." + e.getKey().key(), e.getValue());
            }
            if (!bot.actions.isEmpty()) {
                List<String> names = new ArrayList<>();
                for (BotAction a : bot.actions) names.add(a.name());
                yml.set(path + "actions", names);
            }
        }
        for (Map.Entry<UUID, Map<BotSettings, Boolean>> entry : playerConfigs.entrySet()) {
            String path = "player-configs." + entry.getKey() + ".";
            for (Map.Entry<BotSettings, Boolean> e : entry.getValue().entrySet()) {
                yml.set(path + e.getKey().key(), e.getValue());
            }
        }
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存假人记录失败：" + e.getMessage());
        }
    }
}
