package cn.starfallplain.sfpmain;

import cn.starfallplain.sfpmain.bot.BotManager;
import cn.starfallplain.sfpmain.display.ScoreboardManager;
import cn.starfallplain.sfpmain.display.TabManager;
import cn.starfallplain.sfpmain.ui.MenuUiCommand;
import cn.starfallplain.sfpmain.ui.UiPreferenceStore;
import cn.starfallplain.sfpmain.chain.ChainListener;
import cn.starfallplain.sfpmain.chain.ChainManager;
import cn.starfallplain.sfpmain.clean.CleanTimePlaceholder;
import cn.starfallplain.sfpmain.clean.FloorCleanManager;
import cn.starfallplain.sfpmain.clean.SweepExceptionStore;
import cn.starfallplain.sfpmain.config.ConfigManager;
import cn.starfallplain.sfpmain.debug.SfpCommand;
import cn.starfallplain.sfpmain.menu.MenuClockJoinListener;
import cn.starfallplain.sfpmain.menu.MenuClockListener;
import cn.starfallplain.sfpmain.menu.MenuClockManager;
import cn.starfallplain.sfpmain.menu.MenuCommand;
import cn.starfallplain.sfpmain.menu.MenuListener;
import cn.starfallplain.sfpmain.punish.PunishListener;
import cn.starfallplain.sfpmain.punish.PunishManager;
import cn.starfallplain.sfpmain.punish.command.PunishCommand;
import cn.starfallplain.sfpmain.teleport.TeleportListener;
import cn.starfallplain.sfpmain.teleport.TeleportManager;
import cn.starfallplain.sfpmain.teleport.TpaListener;
import cn.starfallplain.sfpmain.teleport.TpaManager;
import cn.starfallplain.sfpmain.teleport.command.BackCommand;
import cn.starfallplain.sfpmain.teleport.command.HomeCommand;
import cn.starfallplain.sfpmain.teleport.command.RtpCommand;
import cn.starfallplain.sfpmain.teleport.command.TpaCommand;
import cn.starfallplain.sfpmain.teleport.command.WarpCommand;
import cn.starfallplain.sfpmain.teleport.gui.TeleportGuiListener;
import cn.starfallplain.sfpmain.trashbin.TrashBinCommand;
import cn.starfallplain.sfpmain.trashbin.TrashBinListener;
import cn.starfallplain.sfpmain.trashbin.TrashBinManager;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * 星落平原主菜单插件主类。
 * <p>
 * 仅保留插件自研功能（菜单、自动扫地、垃圾桶、传送）与 Dominion 领地入口；
 * 不再依赖 HuskHomes / Vault 等经济与传送插件。
 * 所有功能均由配置文件驱动，开关关闭的模块不会被实例化、不注册监听器。
 * <p>
 * 本插件是 <b>Paper 插件</b>（paper-plugin.yml）：命令不能在描述文件里声明，
 * 统一在 {@link #registerCommands()} 中通过 {@code LifecycleEvents.COMMANDS} 注册。
 */
public final class SfpMain extends JavaPlugin {

    private ConfigManager configManager;

    private FloorCleanManager floorCleanManager;
    private SweepExceptionStore sweepExceptionStore;
    private ChainManager chainManager;
    private TrashBinManager trashBinManager;
    private TeleportManager teleportManager;
    /** 玩家间传送请求（/tpa /tpahere /tpaccept /tpdeny），随传送系统一同创建 */
    private TpaManager tpaManager;
    /** 假人（/bot）：真玩家实体，用于保持区块加载 */
    private BotManager botManager;
    /** 界面样式偏好（dialogUI / 箱子），存 data/settings.db（旧版根目录自动迁移） */
    private UiPreferenceStore uiPreferenceStore;
    /** Tab 列表头部 / 底部（display 包） */
    private TabManager tabManager;
    /** 右侧计分板（display 包） */
    private ScoreboardManager scoreboardManager;
    /** 菜单钟：合成获得、右键打开菜单（menu 包） */
    private MenuClockManager menuClockManager;
    /** 处罚系统：定时封禁 / 禁言 / 踢出 / 警告（punish 包，独立 data/punish.db） */
    private PunishManager punishManager;

    @Override
    public void onEnable() {
        // 1) 加载全部配置（含各模块开关）
        configManager = new ConfigManager(this);

        boolean papiOk = Bukkit.getPluginManager().getPlugin("PlaceholderAPI") != null;

        // 2) 按开关装配各功能模块（垃圾桶须先于扫地创建；传送须先于命令注册）
        //
        //    punish-only（仅处罚）模式下，群组服的处罚子服只跑处罚系统 + /sfp，
        //    其余模块一律不装配：不建数据库、不注册监听器、不生成实体，
        //    这样既避免与主服重复加载，也防止权限与 UI 互相干扰。
        if (configManager.global().isPunishOnly()) {
            getLogger().info("工作模式：仅处罚（punish-only）—— 只启动处罚系统与 /sfp 管理命令。");
        } else {
            setupTrashBin();
            setupSweepExceptions();
            setupFloorClean();
            setupChain();
            setupTeleport();
            setupBot();
            setupUiPreferences();
            setupMenu();
            setupMenuClock();
            setupTab();
            setupScoreboard();
        }
        setupPunish();

        // 3) 注册命令（依赖上面已建好的各管理器，故放在最后）
        registerCommands();

        // 4) PAPI 占位符
        setupPlaceholders(papiOk);

        // 5) 汇总启动状态
        getLogger().info("============== 星落平原 功能状态 ==============");
        for (String line : configManager.describeState(papiOk)) {
            getLogger().info(line);
        }
        getLogger().info("==============================================");
        getLogger().info("SFP-main 已启用！（模式：" + configManager.global().modeName() + "）");
    }

    @Override
    public void onDisable() {
        // 保存垃圾桶数据，避免直接关服丢失
        if (trashBinManager != null) {
            trashBinManager.save();
        }
        if (sweepExceptionStore != null) {
            sweepExceptionStore.close();
        }
        // 关闭传送数据库连接
        if (teleportManager != null) {
            teleportManager.shutdown();
        }
        // 取消未过期的 tpa 请求任务
        if (tpaManager != null) {
            tpaManager.shutdown();
        }
        // 保存假人记录（假人实体本身随服务端关闭而消失，下次启动按记录重建）
        if (botManager != null) {
            botManager.saveData();
            botManager.shutdown();
        }
        // 关闭界面偏好数据库
        if (uiPreferenceStore != null) {
            uiPreferenceStore.close();
        }
        // 停掉显示相关的定时任务
        if (tabManager != null) {
            tabManager.shutdown();
        }
        if (scoreboardManager != null) {
            scoreboardManager.shutdown();
        }
        // 注销菜单钟的合成配方（否则会留下一个合得出来但用不了的物品）
        if (menuClockManager != null) {
            menuClockManager.unregisterRecipe();
        }
        // 关闭处罚数据库连接
        if (punishManager != null) {
            punishManager.shutdown();
        }
        getLogger().info("SFP-main 已禁用！");
    }

    // ==================== 启动装配 ====================

    /**
     * 注册全部命令。
     * <p>
     * Paper 插件不支持 plugin.yml 的 {@code commands} 段，改为注册到本插件自己的
     * {@code LifecycleEvents.COMMANDS} 生命周期事件：Paper 会在需要时（含 /reload）
     * 重新触发该事件，命令随之重新注册，无需自行处理重载。
     */
    private void registerCommands() {
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            Commands registrar = event.registrar();

            // 仅处罚模式：只注册处罚命令与 /sfp 管理命令，其余命令完全不注册
            // （连「未启用」桩也不提供，避免群组服玩家看到一堆无用命令）。
            if (configManager.global().isPunishOnly()) {
                registrar.register("sfp", "星落平原管理命令（输入 /sfp 查看用法）", new SfpCommand(this));
                registerPunishCommands(registrar);
                return;
            }

            // 主菜单：/menu 与简写 /m（别名）
            registrar.register("menu", "打开星落平原菜单", List.of("m"), new MenuCommand(this));

            // 垃圾桶
            registrar.register("trashbin", "打开扫地垃圾桶，取回被清扫的掉落物",
                    new TrashBinCommand(this));

            // 传送命令
            registerTeleportCommands(registrar);

            // 界面样式切换：/menuui dialogui|box
            registrar.register("menuui", "切换界面样式（dialogUI / 箱子）", new MenuUiCommand(this));

            // 管理 / 调试命令 /sfp：reload / status / db / test / sweep
            registrar.register("sfp", "星落平原管理命令（输入 /sfp 查看用法）", new SfpCommand(this));

            // 处罚系统命令（7 个）
            registerPunishCommands(registrar);
        });
    }

    /**
     * 注册处罚命令（/sfpcheck /sfpwarn /sfpkick /sfpban /sfpmute /sfpunban /sfpunmute）。
     * <p>
     * {@link BasicCommand} 拿不到命令标签，故每个标签各注册一个 {@link PunishCommand} 实例，
     * 用构造参数区分动作。处罚模块关闭时仍注册同名命令，统一回「功能未启用」。
     */
    private void registerPunishCommands(Commands registrar) {
        if (punishManager == null) {
            BasicCommand disabled = (source, args) -> source.getSender().sendMessage(
                    getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            for (String label : new String[]{"sfpcheck", "sfpwarn", "sfpkick", "sfpban",
                    "sfpmute", "sfpunban", "sfpunmute"}) {
                registrar.register(label, "处罚功能（当前未启用）", disabled);
            }
            return;
        }
        registrar.register("sfpcheck", "查询处罚记录（按处罚ID 或 玩家名）",
                new PunishCommand(this, punishManager, "check"));
        registrar.register("sfpwarn", "警告在线玩家",
                new PunishCommand(this, punishManager, "warn"));
        registrar.register("sfpkick", "踢出在线玩家",
                new PunishCommand(this, punishManager, "kick"));
        registrar.register("sfpban", "封禁玩家（可离线，需指定时间）",
                new PunishCommand(this, punishManager, "ban"));
        registrar.register("sfpmute", "禁言玩家（可离线，需指定时间）",
                new PunishCommand(this, punishManager, "mute"));
        registrar.register("sfpunban", "解除封禁（目标取自数据库）",
                new PunishCommand(this, punishManager, "unban"));
        registrar.register("sfpunmute", "解除禁言（目标取自数据库）",
                new PunishCommand(this, punishManager, "unmute"));
    }

    /**
     * 注册传送命令（/back /rtp /home /sethome /delhome /homes /warp /setwarp /delwarp /warps
     * 以及 /tpa /tpahere /tpaccept /tpdeny /tpaui）。
     * <p>
     * {@code BackCommand} 等实现的是 {@link BasicCommand}，拿不到命令标签，
     * 因此 Home / Warp / Tpa 系列由构造参数区分动作，每个标签各注册一个实例。
     * <p>
     * 传送模块被关闭（{@code teleportManager == null}）时仍注册同名命令，
     * 统一回复「功能未启用」，避免玩家误以为命令不存在。
     */
    private void registerTeleportCommands(Commands registrar) {
        if (teleportManager == null) {
            BasicCommand disabled = (source, args) -> source.getSender().sendMessage(
                    getMessage("common.feature-disabled", "<red>该功能当前未启用。</red>"));
            for (String label : new String[]{"back", "home", "sethome", "delhome", "homes",
                    "warp", "setwarp", "delwarp", "warps",
                    "tpa", "tpahere", "tpaccept", "tpdeny", "tpaui", "rtp"}) {
                registrar.register(label, "传送功能（当前未启用）", disabled);
            }
            return;
        }

        registrar.register("back", "返回上一位置（上次传送前 / 死亡点）",
                new BackCommand(this, teleportManager));

        // /rtp 随机传送（无参数，在当前世界随机找点）
        registrar.register("rtp", "随机传送（在允许的世界范围内随机找一处安全落点）",
                new RtpCommand(this, teleportManager));

        // /home /sethome /delhome /homes 共用一套逻辑，用 action 区分
        registrar.register("home", "打开家列表或传送到指定家",
                new HomeCommand(this, teleportManager, "home"));
        registrar.register("sethome", "设置个人家",
                new HomeCommand(this, teleportManager, "sethome"));
        registrar.register("delhome", "删除个人家",
                new HomeCommand(this, teleportManager, "delhome"));
        registrar.register("homes", "列出所有个人家",
                new HomeCommand(this, teleportManager, "homes"));

        // /warp /setwarp /delwarp /warps
        registrar.register("warp", "打开传送点列表或传送到指定传送点",
                new WarpCommand(this, teleportManager, "warp"));
        registrar.register("setwarp", "创建公共传送点",
                new WarpCommand(this, teleportManager, "setwarp"));
        registrar.register("delwarp", "删除公共传送点",
                new WarpCommand(this, teleportManager, "delwarp"));
        registrar.register("warps", "列出所有公共传送点",
                new WarpCommand(this, teleportManager, "warps"));

        // /tpa /tpahere /tpaccept /tpdeny /tpaui
        registrar.register("tpa", "请求传送到某玩家身边",
                new TpaCommand(this, tpaManager, "tpa"));
        registrar.register("tpahere", "请求某玩家传送到你身边",
                new TpaCommand(this, tpaManager, "tpahere"));
        registrar.register("tpaccept", "接受传送请求",
                new TpaCommand(this, tpaManager, "tpaccept"));
        registrar.register("tpdeny", "拒绝传送请求",
                new TpaCommand(this, tpaManager, "tpdeny"));
        registrar.register("tpaui", "切换传送请求的回应界面（弹窗 / 聊天按钮）",
                new TpaCommand(this, tpaManager, "tpaui"));
    }

    private void setupTrashBin() {
        if (!configManager.trashBin().isEnabled()) {
            getLogger().info("垃圾桶：已按配置关闭。");
            return;
        }
        trashBinManager = new TrashBinManager(this, configManager);
        getServer().getPluginManager().registerEvents(new TrashBinListener(this), this);
        getLogger().info("垃圾桶系统已启动（/trashbin 打开）。");
    }

    private void setupSweepExceptions() {
        sweepExceptionStore = new SweepExceptionStore(this);
    }

    private void setupFloorClean() {
        if (!configManager.clean().isEnabled()) {
            getLogger().info("自动扫地：已按配置关闭。");
            return;
        }
        floorCleanManager = new FloorCleanManager(this, configManager, trashBinManager, sweepExceptionStore);
        getLogger().info("扫地系统已启动（周期 " + floorCleanManager.formatTime() + "，"
                + (floorCleanManager.getConfig().isToTrashBin() && trashBinManager != null
                        ? "物品收入垃圾桶" : "物品直接删除") + "）。");
    }

    /**
     * 连锁挖矿：只注册一个 {@code BlockBreakEvent} 监听器，没有后台任务与状态。
     * <p>
     * 装配顺序上放在扫地之后 —— 两者都会碰到掉落物，但扫地只处理实体物品，
     * 连锁只处理方块，互不干扰。
     */
    private void setupChain() {
        if (!configManager.chain().isEnabled()) {
            getLogger().info("连锁挖矿：已按配置关闭。");
            return;
        }
        chainManager = new ChainManager(this);
        getServer().getPluginManager().registerEvents(new ChainListener(this), this);
        int max = configManager.chain().getMaxBlocks();
        getLogger().info("连锁挖矿已启动（蹲下挖矿触发，单次上限 " + max + " 块）。");
    }

    /**
     * 界面样式偏好（dialogUI / 箱子）：独立于传送模块的小型 SQLite。
     */
    private void setupUiPreferences() {
        uiPreferenceStore = new UiPreferenceStore(this);
    }

    /**
     * Tab 列表（display 包）：头部 / 底部文字，内容与频率全部来自 display.yml 的 tab 段。
     */
    private void setupTab() {
        if (!configManager.display().isTabEnabled()) return;
        tabManager = new TabManager(this, configManager);
    }

    /**
     * 右侧计分板（display 包）：标题与行来自 display.yml 的 scoreboard 段。
     */
    private void setupScoreboard() {
        if (!configManager.display().isScoreboardEnabled()) return;
        scoreboardManager = new ScoreboardManager(this, configManager);
        Bukkit.getPluginManager().registerEvents(scoreboardManager, this);
    }

    private void setupMenu() {
        if (!configManager.menu().isEnabled()) {
            getLogger().info("主菜单：已按配置关闭（/menu 将提示未启用）。");
            return;
        }
        getServer().getPluginManager().registerEvents(new MenuListener(this), this);
        getLogger().info("主菜单已启动（/menu 或 /m）。");
    }

    /**
     * 菜单钟：合成得到的「钟」，手持右键打开菜单。
     * <p>
     * 依赖主菜单本身 —— 菜单关闭时不注册（否则合出来的钟点了没反应）。
     */
    private void setupMenuClock() {
        if (!configManager.menu().isEnabled()) {
            return;
        }
        if (!configManager.menu().isMenuClockEnabled()) {
            getLogger().info("菜单钟：已按配置关闭。");
            return;
        }
        menuClockManager = new MenuClockManager(this, configManager);
        menuClockManager.registerRecipe();
        getServer().getPluginManager().registerEvents(new MenuClockListener(this, menuClockManager), this);
        // 进服提示「可以合成菜单钟」
        getServer().getPluginManager().registerEvents(
                new MenuClockJoinListener(this, configManager.menu()), this);
        getLogger().info("菜单钟已启用（右键打开菜单）。");
    }

    /**
     * 处罚系统：定时封禁 / 禁言 / 踢出 / 警告。
     * <p>
     * 数据存独立的 {@code data/punish.db}（旧版根目录自动迁移）；命令在 {@link #registerCommands()} 里统一注册。
     * 「惰性到期判定」靠 {@link PunishListener} 挂在进服 / 发言 / 查询时机上，不轮询。
     */
    private void setupPunish() {
        if (!configManager.punish().isEnabled()) {
            getLogger().info("处罚系统：已按配置关闭。");
            return;
        }
        punishManager = new PunishManager(this, configManager.punish());
        if (!punishManager.isStorageAvailable()) {
            getLogger().warning("处罚数据库初始化失败，处罚功能将不可用。");
        }
        getServer().getPluginManager().registerEvents(new PunishListener(this, punishManager), this);
        getLogger().info("处罚系统已启动（/sfpban /sfpmute /sfpkick /sfpwarn /sfpcheck）。");
        getLogger().info("处罚数据库：" + punishManager.getDatabase().getFile().getAbsolutePath());
    }

    /**
     * 传送系统（/back /home /warp）：初始化 SQLite 数据层并注册监听器。
     * 命令在 {@link #registerCommands()} 中统一注册（此时 teleportManager 已存在）。
     */
    private void setupTeleport() {
        if (!configManager.teleport().isEnabled()) {
            getLogger().info("传送系统：已按配置关闭。");
            return;
        }
        teleportManager = new TeleportManager(this, configManager.teleport());
        if (!teleportManager.isStorageAvailable()) {
            getLogger().warning("传送数据库初始化失败，/back /home /warp 将不可用。");
        }
        // 玩家间传送请求（会话态，不入库；与传送系统同生命周期）
        tpaManager = new TpaManager(this, configManager.teleport(), teleportManager);

        getServer().getPluginManager().registerEvents(new TeleportListener(teleportManager, configManager.teleport()), this);
        getServer().getPluginManager().registerEvents(new TeleportGuiListener(this, teleportManager), this);
        getServer().getPluginManager().registerEvents(new TpaListener(tpaManager), this);

        getLogger().info("传送系统已启动（/back /rtp /home /warp"
                + (tpaManager.isEnabled() ? " /tpa /tpahere" : "") + "，数据存储于 SQLite）。");
    }

    /**
     * 假人（{@code /sfp player} / {@code /sfp fp}）：创建真玩家实体，可保区块加载、
     * 挂机攻击/挖掘/钓鱼，详见 bot 包。
     * <p>
     * 假人的重建放在下一 tick 而不是 onEnable 里同步做：加入玩家会触发服务端的一整套
     * 加入流程，让它在插件启用栈之外执行更稳妥（此时世界已加载完毕，满足重建条件）。
     * 持续动作的周期任务立即启动（此时 bots.yml 已读进内存，任务会自然跳过未加入的假人）。
     */
    private void setupBot() {
        if (!configManager.bot().isEnabled()) {
            getLogger().info("假人：已按配置关闭。");
            return;
        }
        botManager = new BotManager(this, configManager);
        botManager.startActions();
        getServer().getPluginManager().registerEvents(new cn.starfallplain.sfpmain.bot.BotListener(this), this);
        getServer().getScheduler().runTask(this, () -> botManager.restoreOnStart());
        getLogger().info("假人系统已启动（/sfp player spawn <名字>）。");
    }

    private void setupPlaceholders(boolean papiOk) {
        // 仅处罚模式下不装配扫地，本插件没有任何占位符要注册，直接跳过。
        if (configManager.global().isPunishOnly()) return;
        if (!papiOk) {
            getLogger().warning("PlaceholderAPI 未安装，扫地倒计时占位符不可用。");
            return;
        }
        if (floorCleanManager != null) {
            new CleanTimePlaceholder(this).register();
            getLogger().info("PlaceholderAPI 占位符 %stf_cleantime% 注册成功！");
        }
        if (botManager != null) {
            new cn.starfallplain.sfpmain.bot.BotPlaceholders(this).register();
            getLogger().info("PlaceholderAPI 占位符 %fakeplayer_total% 等注册成功！");
        }
    }

    // ==================== 重载 ====================

    /**
     * 重载全部配置。注意：开关状态变更需要重启服务器才能完全生效
     * （监听器注册不可逆），参数类修改立即生效。
     */
    public boolean reloadAll() {
        try {
            configManager.reload();
            return true;
        } catch (Throwable t) {
            getLogger().warning("重载配置失败：" + t.getMessage());
            return false;
        }
    }

    /** 便捷取消息文案并解析 */
    public Component getMessage(String key, String fallback) {
        return configManager.messages().component(key, fallback);
    }

    /** 取原始文案（未解析） */
    public String getRawMessage(String key, String fallback) {
        return configManager.messages().raw(key, fallback);
    }

    /** 取原始文案列表（未解析） */
    public List<String> getRawMessageList(String key, List<String> fallback) {
        List<String> list = configManager.messages().rawList(key);
        return list.isEmpty() ? fallback : list;
    }

    // ==================== 访问器 ====================

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public FloorCleanManager getFloorCleanManager() {
        return floorCleanManager;
    }

    public SweepExceptionStore getSweepExceptionStore() {
        return sweepExceptionStore;
    }

    public ChainManager getChainManager() {
        return chainManager;
    }

    public TrashBinManager getTrashBinManager() {
        return trashBinManager;
    }

    public TeleportManager getTeleportManager() {
        return teleportManager;
    }

    /** 玩家传送请求管理器；传送系统关闭时为 null */
    public TpaManager getTpaManager() {
        return tpaManager;
    }

    /** 假人管理器；bot.yml 关闭时为 null */
    public BotManager getBotManager() {
        return botManager;
    }

    /** 界面样式偏好存储 */
    public UiPreferenceStore getUiPreferenceStore() {
        return uiPreferenceStore;
    }

    /** 菜单钟管理器；菜单或菜单钟关闭时为 null */
    public MenuClockManager getMenuClockManager() {
        return menuClockManager;
    }

    /** 处罚管理器；punish.yml 关闭时为 null */
    public PunishManager getPunishManager() {
        return punishManager;
    }
}
