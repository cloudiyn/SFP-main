package cn.starfallplain.sfpmain.bot;

import cn.starfallplain.sfpmain.SfpMain;
import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;

/**
 * PAPI 占位符：假人（对齐 minecraft-fakeplayer 的 placeholder variables）。
 * <pre>
 *   %fakeplayer_total%            假人总数（记录数）
 *   %fakeplayer_online%           其中已加入服务端的数量
 *   %fakeplayer_creator_&lt;名字&gt;%   某个假人的创建者名
 *   %fakeplayer_actions_&lt;名字&gt;%   某个假人的持续动作（如 攻击|挖掘，没有则为空）
 * </pre>
 * 只在装有 PlaceholderAPI 时注册（见 SfpMain#setupPlaceholders）。
 */
public class BotPlaceholders extends PlaceholderExpansion {

    private final SfpMain plugin;

    public BotPlaceholders(SfpMain plugin) {
        this.plugin = plugin;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "fakeplayer";
    }

    @Override
    public @NotNull String getAuthor() {
        return "CN_Ran";
    }

    @Override
    public @NotNull String getVersion() {
        return "1.0.0";
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public String onRequest(OfflinePlayer player, @NotNull String params) {
        BotManager manager = plugin.getBotManager();
        if (manager == null) return "";

        if (params.equalsIgnoreCase("total")) {
            return String.valueOf(manager.size());
        }
        if (params.equalsIgnoreCase("online")) {
            return String.valueOf(manager.onlineCount());
        }
        String lower = params.toLowerCase();
        if (lower.startsWith("creator_")) {
            BotManager.Bot bot = manager.byName(params.substring("creator_".length()));
            if (bot == null || bot.owner == null) return "";
            String name = org.bukkit.Bukkit.getOfflinePlayer(bot.owner).getName();
            return name == null ? "" : name;
        }
        if (lower.startsWith("actions_")) {
            BotManager.Bot bot = manager.byName(params.substring("actions_".length()));
            return bot == null ? "" : manager.actions().describe(bot);
        }
        return null;
    }
}
