package cn.starfallplain.sfpmain.bot;

import org.bukkit.entity.Player;

/** 会话绑定实体实例；同 UUID 的假人重建后不能继续使用旧界面。 */
final class BotInventorySession {
    final Player viewer;
    final Player target;
    final BotInventoryHolder holder;
    boolean closing;

    BotInventorySession(Player viewer, Player target, BotInventoryHolder holder) {
        this.viewer = viewer;
        this.target = target;
        this.holder = holder;
    }
}
