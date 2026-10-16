package cn.starfallplain.sfpmain.config.module;

import cn.starfallplain.sfpmain.config.AbstractConfig;
import cn.starfallplain.sfpmain.config.Messages;
import cn.starfallplain.sfpmain.punish.PunishmentType;
import net.kyori.adventure.text.Component;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Map;

/**
 * 处罚系统配置（punish.yml）。
 * <p>
 * 与其它模块不同：本模块<b>所有面向玩家的文案都放在本文件</b>（不回退 messages.yml），
 * 因为「自定义踢人 / 封禁消息」是这项功能的核心诉求之一，集中在一处最好改。
 * <p>
 * 支持 MiniMessage 标签，占位符统一用花括号：{@code {player} {operator} {reason} {type}
 * {id} {expire} {duration}}。{@code reason} 若本身含 MiniMessage 标签会被原样解析，
 * 因此管理员可以在处罚原因里写颜色。
 */
public final class PunishConfig extends AbstractConfig {

    private boolean enabled;

    // 数据库
    private String dbFile;
    private boolean autoCommit;

    // 通用
    private String defaultReason;
    private String permanentText;
    /** 踢出前的延迟 tick 数：代理环境下需要 >0，见字段下方注释与 PunishListener#onJoin */
    private int kickDelayTicks;

    // 音效
    private String soundPunish;
    private String soundRevoke;

    // ===== 玩家提示文案 =====
    private String muteNotice;          // 被禁言者发言时返回
    private String kickScreenBan;       // 封禁踢下线整屏
    private String kickScreenKick;      // 主动踢出整屏

    // ===== 执行反馈（给管理员 / 全服）=====
    private boolean broadcastPunish;
    private String broadcastFormat;

    public PunishConfig(JavaPlugin plugin) {
        super(plugin, "punish.yml");
    }

    @Override
    protected void onLoaded() {
        enabled = getBoolean("enabled", true);

        dbFile = getString("database.file", "punish.db");
        autoCommit = getBoolean("database.auto-commit", true);

        defaultReason = getString("default-reason", "违反服务器规则");
        permanentText = getString("permanent-text", "永久");
        // 至少 1 tick：0 会让代理把「刚连上就被踢」当成连接中断而重投下一个子服
        kickDelayTicks = Math.max(1, getInt("kick-delay-ticks", 1));

        soundPunish = getString("sounds.punish", "ENTITY_VILLAGER_NO");
        soundRevoke = getString("sounds.revoke", "BLOCK_ANVIL_LAND");

        // 注：2.11.0 起不再有 messages.join-warn-notice（原「进服提示有未读处罚」）。
        // 警告（WARN）按设计不写生效表、也没有「已读」状态，无法判定「未读」，
        // 该键从来没有任何调用点，已随本次清理从 punish.yml 移除。
        muteNotice = getString("messages.mute-notice",
                "<red>你已被禁言</red><newline>"
                        + "<gray>原因：</gray><white>{reason}</white><newline>"
                        + "<gray>剩余时间：</gray><white>{expire}</white>");
        kickScreenBan = getString("messages.kick-screen-ban",
                "<dark_red><bold>你已被封禁</bold></dark_red>\n\n"
                        + "<gray>处罚编号：</gray><white>{id}</white>\n"
                        + "<gray>原因：</gray><white>{reason}</white>\n"
                        + "<gray>剩余时间：</gray><white>{expire}</white>\n"
                        + "<gray>操作者：</gray><white>{operator}</white>\n\n"
                        + "<dark_gray>如有疑问请联系服主申诉。</dark_gray>");
        kickScreenKick = getString("messages.kick-screen-kick",
                "<red><bold>你已被移出服务器</bold></red>\n\n"
                        + "<gray>处罚编号：</gray><white>{id}</white>\n"
                        + "<gray>原因：</gray><white>{reason}</white>\n"
                        + "<gray>操作者：</gray><white>{operator}</white>");

        broadcastPunish = getBoolean("broadcast.enabled", true);
        broadcastFormat = getString("broadcast.format",
                "<gray>[</gray><red>处罚</red><gray>]</gray> <white>{player}</white> "
                        + "<gray>被</gray><yellow>{operator}</yellow><gray>执行</gray>"
                        + "<red>{type}</red><gray>（</gray><white>{duration}</white><gray>）</gray>"
                        + "<gray>，原因：</gray><white>{reason}</white>");
    }

    // ==================== 访问器 ====================

    public boolean isEnabled() { return enabled; }

    public String getDbFile() { return dbFile; }

    public boolean isAutoCommit() { return autoCommit; }

    public String getDefaultReason() { return defaultReason; }

    /** 「永久」的展示文本（占位符 {expire} / {duration} 用） */
    public String getPermanentText() { return permanentText; }

    /** 踢出玩家前的延迟 tick 数（默认 1；代理/跨服环境下不能为 0） */
    public int getKickDelayTicks() { return kickDelayTicks; }

    public String getSoundPunish() { return soundPunish; }

    public String getSoundRevoke() { return soundRevoke; }

    public boolean isBroadcastPunish() { return broadcastPunish; }

    public String getBroadcastFormat() { return broadcastFormat; }

    // ==================== 文案渲染 ====================

    /**
     * 构建「踢下线整屏」消息。
     * 封禁与踢出用不同模板（封禁显示剩余时间，踢出不显示）。
     */
    public Component kickScreen(PunishmentType type, Map<String, String> placeholders) {
        String template = type == PunishmentType.BAN ? kickScreenBan : kickScreenKick;
        return Messages.deserialize(Messages.apply(template, placeholders));
    }

    /** 构建禁言提示（聊天被拦截时发回给发言者） */
    public Component muteNotice(Map<String, String> placeholders) {
        return Messages.deserialize(Messages.apply(muteNotice, placeholders));
    }

    /** 构建全服广播文本；关闭时返回 null */
    public Component broadcast(Map<String, String> placeholders) {
        if (!broadcastPunish) return null;
        return Messages.deserialize(Messages.apply(broadcastFormat, placeholders));
    }

    /** 记录一条执行日志（写插件日志，便于事后追查） */
    public void log(String message) {
        plugin.getLogger().info("[处罚] " + message);
    }
}
