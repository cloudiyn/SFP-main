package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.config.module.BotConfig;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 假人管理（{@code /bot}）。
 * <p>
 * 假人是通过 {@link NmsBotFactory} 创建的真玩家实体（NMS ServerPlayer），
 * 因此它天然保持周围区块加载 —— 这就是「挂机假人」保区块的原理。
 * <p>
 * 假人没有会话，**服务器重启后必须重新加入**：所以这里把每个假人记在
 * 数据目录的 {@code bots.yml}（名字 / UUID / 创建者 / 位置），启动时按记录重建。
 * <p>
 * 名字即身份：UUID 由名字派生（{@code StarfallBot:名字}），所以同一个名字永远是同一个档案，
 * 重名会被拒绝。
 */
public class BotManager {

    /** 名字规则：Minecraft 玩家名限制 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /** 一个假人的记录 */
    private record Bot(String name, UUID uuid, UUID owner, String world,
                       double x, double y, double z, float yaw, float pitch) {
    }

    private final SfpMain plugin;
    private final BotConfig config;
    private final File dataFile;
    /** 小写名字 → 记录 */
    private final Map<String, Bot> bots = new LinkedHashMap<>();
    /** 定期清理假连接积压数据包的任务 */
    private BukkitTask drainTask;

    public BotManager(SfpMain plugin, ConfigManager configManager) {
        this.plugin = plugin;
        this.config = configManager.bot();
        this.dataFile = new File(plugin.getDataFolder(), "bots.yml");
        loadData();
        startDrainTask();
    }

    /**
     * 定期清空假人连接上积压的数据包（每 60 秒一次）。
     * <p>
     * 假人的「网络连接」是一个被当作吞包黑洞的 EmbeddedChannel，服务端发给它的包
     * 没人消费，不清就会一直堆积。
     */
    private void startDrainTask() {
        drainTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Bot bot : bots.values()) {
                Player online = Bukkit.getPlayer(bot.uuid());
                if (online != null) {
                    NmsBotFactory.drainPendingPackets(online);
                }
            }
        }, 1200L, 1200L);
    }

    /** 插件卸载：停掉清理任务（假人实体本身随服务端关闭而消失） */
    public void shutdown() {
        if (drainTask != null) {
            drainTask.cancel();
            drainTask = null;
        }
    }

    public BotConfig getConfig() {
        return config;
    }

    /** 当前记录的假人数量（供 /sfp status 与自检显示运行态） */
    public int size() {
        return bots.size();
    }

    /** 其中已成功加入服务端（在线）的数量 */
    public int onlineCount() {
        int online = 0;
        for (Bot bot : bots.values()) {
            if (Bukkit.getPlayer(bot.uuid()) != null) online++;
        }
        return online;
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
            World world = Bukkit.getWorld(bot.world());
            if (world == null) {
                plugin.getLogger().warning("假人 " + bot.name() + " 重建失败：世界 " + bot.world()
                        + " 不存在（记录保留，世界恢复后下次启动会再试）。");
                continue;
            }
            if (Bukkit.getPlayer(bot.uuid()) != null) {
                ok++;
                continue;
            }
            try {
                NmsBotFactory.spawn(plugin, resolveSkinSource(null), world,
                        toLocation(bot, world), bot.name(), bot.uuid());
                ok++;
            } catch (Throwable t) {
                plugin.getLogger().warning("假人 " + bot.name() + " 重建失败：" + t);
            }
        }
        plugin.getLogger().info("假人：已重建 " + ok + "/" + bots.size() + " 个。");
    }

    // ==================== 创建 / 删除 ====================

    /** 创建假人；成功返回 true。所有提示都直接发给 sender。 */
    public boolean create(CommandSender sender, String rawName, World world, Location loc) {
        String name = rawName == null ? "" : rawName.trim();

        if (!NAME_PATTERN.matcher(name).matches() || name.length() > config.getMaxNameLength()) {
            Map<String, String> ph = new HashMap<>();
            ph.put("max", String.valueOf(config.getMaxNameLength()));
            plugin.getConfigManager().messages().send(sender, "bot.invalid-name",
                    "<red>假人名字只能包含字母、数字、下划线，长度 1~{max}。</red>", ph);
            return false;
        }
        if (bots.containsKey(name.toLowerCase())) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(sender, "bot.exists",
                    "<red>已存在名为「{name}」的假人。</red>", ph);
            return false;
        }

        UUID owner = sender instanceof Player player ? player.getUniqueId() : null;
        int max = config.getMaxPerPlayer();
        if (max > 0 && owner != null) {
            long mine = bots.values().stream().filter(b -> owner.equals(b.owner())).count();
            if (mine >= max) {
                Map<String, String> ph = new HashMap<>();
                ph.put("max", String.valueOf(max));
                plugin.getConfigManager().messages().send(sender, "bot.limit-reached",
                        "<red>你创建的假人已达上限（{max} 个）。</red>", ph);
                return false;
            }
        }

        UUID uuid = deriveUuid(name);
        try {
            NmsBotFactory.spawn(plugin, resolveSkinSource(sender instanceof Player p ? p : null),
                    world, loc, name, uuid);
        } catch (Throwable t) {
            plugin.getLogger().warning("创建假人 " + name + " 失败：" + t);
            Map<String, String> ph = new HashMap<>();
            ph.put("error", String.valueOf(t));
            plugin.getConfigManager().messages().send(sender, "bot.create-failed",
                    "<red>创建假人失败：{error}</red>", ph);
            return false;
        }

        Bot bot = new Bot(name, uuid, owner, world.getName(),
                loc.getX(), loc.getY(), loc.getZ(), loc.getYaw(), loc.getPitch());
        bots.put(name.toLowerCase(), bot);
        saveData();

        Map<String, String> ph = new HashMap<>();
        ph.put("name", name);
        ph.put("location", world.getName() + " " + Math.round(loc.getX()) + ", "
                + Math.round(loc.getY()) + ", " + Math.round(loc.getZ()));
        plugin.getConfigManager().messages().send(sender, "bot.created",
                "<green>已创建假人「{name}」（{location}），它会保持所在区块加载。</green>", ph);
        return true;
    }

    /** 删除假人 */
    public boolean remove(CommandSender sender, String rawName) {
        String name = rawName == null ? "" : rawName.trim();
        Bot bot = bots.remove(name.toLowerCase());
        if (bot == null) {
            Map<String, String> ph = new HashMap<>();
            ph.put("name", name);
            plugin.getConfigManager().messages().send(sender, "bot.not-found",
                    "<red>不存在名为「{name}」的假人。</red>", ph);
            return false;
        }
        kick(bot);
        saveData();

        Map<String, String> ph = new HashMap<>();
        ph.put("name", bot.name());
        plugin.getConfigManager().messages().send(sender, "bot.removed",
                "<green>已移除假人「{name}」。</green>", ph);
        return true;
    }

    /** 删除全部假人 */
    public void removeAll(CommandSender sender) {
        int count = bots.size();
        for (Bot bot : new ArrayList<>(bots.values())) {
            kick(bot);
        }
        bots.clear();
        saveData();

        Map<String, String> ph = new HashMap<>();
        ph.put("count", String.valueOf(count));
        plugin.getConfigManager().messages().send(sender, "bot.removed-all",
                "<green>已移除全部假人（{count} 个）。</green>", ph);
    }

    /** 列出全部假人 */
    public void list(CommandSender sender) {
        Map<String, String> ph = new HashMap<>();
        ph.put("count", String.valueOf(bots.size()));
        plugin.getConfigManager().messages().send(sender, "bot.list-header",
                "<aqua>假人（{count}）：</aqua>", ph);

        if (bots.isEmpty()) {
            plugin.getConfigManager().messages().send(sender, "bot.list-empty",
                    "<yellow>当前没有假人。</yellow>", Map.of());
            return;
        }
        for (Bot bot : bots.values()) {
            boolean online = Bukkit.getPlayer(bot.uuid()) != null;
            Map<String, String> entry = new HashMap<>();
            entry.put("name", bot.name());
            entry.put("world", bot.world());
            entry.put("x", String.valueOf(Math.round(bot.x())));
            entry.put("y", String.valueOf(Math.round(bot.y())));
            entry.put("z", String.valueOf(Math.round(bot.z())));
            entry.put("state", online ? "在线" : "未加入");
            plugin.getConfigManager().messages().send(sender, "bot.list-entry",
                    "<gray> - </gray><white>{name}</white> <dark_gray>({world} {x}, {y}, {z}) {state}</dark_gray>",
                    entry);
        }
    }

    /** 假人名字列表（供命令补全） */
    public java.util.List<String> names() {
        return bots.values().stream().map(Bot::name).toList();
    }

    // ==================== 内部 ====================

    /**
     * 把假人从服务端摘掉。
     * <p>
     * <b>不能用 Bukkit 的 {@code kick()}</b>：它依赖向客户端发送断开包，而假人没有真正的
     * 客户端（连接是一条被吞包的空管道），实测调用后假人依旧留在在线列表里。
     * 因此直接调用服务端的 {@code PlayerList#remove(ServerPlayer)}，它会走完移除流程：
     * 从在线列表 / UUID 索引 / 名字索引里摘掉，并广播退出消息、保存玩家数据。
     */
    private void kick(Bot bot) {
        Player online = Bukkit.getPlayer(bot.uuid());
        if (online == null) return;

        try {
            Object craftServer = Bukkit.getServer();
            Object playerList = craftServer.getClass().getMethod("getHandle").invoke(craftServer);
            Object nmsPlayer = online.getClass().getMethod("getHandle").invoke(online);

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
            plugin.getLogger().warning("假人 " + bot.name() + " 从在线列表移除失败：" + t);
        }

        // 再确保实体从世界里消失（已经在移除流程里被清掉时会抛异常，忽略即可）
        try {
            online.remove();
        } catch (Throwable ignored) {
            // 已经从世界移除
        }
    }

    /**
     * 皮肤来源：
     * <ul>
     *   <li>配置留空 —— 用创建者自己的皮肤（控制台创建时为 null → 默认皮肤）</li>
     *   <li>{@code none} —— 默认皮肤</li>
     *   <li>玩家名 —— 该玩家的皮肤</li>
     * </ul>
     */
    private Player resolveSkinSource(Player creator) {
        String source = config.getSkinSource();
        if (source == null || source.isBlank()) return creator;
        if (source.equalsIgnoreCase("none")) return null;
        return Bukkit.getPlayerExact(source);
    }

    private Location toLocation(Bot bot, World world) {
        return new Location(world, bot.x(), bot.y(), bot.z(), bot.yaw(), bot.pitch());
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
        if (section == null) return;

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
                bots.put(key.toLowerCase(), bot);
            } catch (Exception e) {
                plugin.getLogger().warning("读取假人记录失败（" + key + "）：" + e.getMessage());
            }
        }
    }

    /** 保存记录（创建 / 删除后都会调用） */
    public void saveData() {
        YamlConfiguration yml = new YamlConfiguration();
        yml.options().setHeader(java.util.List.of(
                "假人记录：服务器重启后插件会按这里的内容重建假人。",
                "一般不需要手动编辑；直接删掉某个条目也能阻止它下次被重建。"));
        for (Bot bot : bots.values()) {
            String path = "bots." + bot.name().toLowerCase() + ".";
            yml.set(path + "name", bot.name());
            yml.set(path + "uuid", bot.uuid().toString());
            yml.set(path + "owner", bot.owner() == null ? "" : bot.owner().toString());
            yml.set(path + "world", bot.world());
            yml.set(path + "x", bot.x());
            yml.set(path + "y", bot.y());
            yml.set(path + "z", bot.z());
            yml.set(path + "yaw", bot.yaw());
            yml.set(path + "pitch", bot.pitch());
        }
        try {
            yml.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("保存假人记录失败：" + e.getMessage());
        }
    }
}
