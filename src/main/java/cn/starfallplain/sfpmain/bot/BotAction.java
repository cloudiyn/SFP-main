package cn.starfallplain.sfpmain.bot;

/**
 * 假人的**持续动作**（周期执行，直到 {@code /sfp player stop} 或假人被移除）。
 * <p>
 * 对齐 minecraft-fakeplayer 的 periodical actions：{@code attack} / {@code mine} /
 * {@code use} / {@code move} 会每隔 {@code bot.yml} 的 {@code action-interval-ticks}
 * 执行一次；一次性动作（jump / sneak / sprint / look / turn / hold / swap / sleep /
 * wakeup / ride 等）不走这里，由 {@link BotCommand} 直接调用。
 */
public enum BotAction {

    /** 攻击：自动攻击附近的实体（范围见 bot.yml 的 attack-range） */
    ATTACK("attack", "攻击"),
    /** 挖掘：持续挖掘假人面朝方向的方块 */
    MINE("mine", "挖掘"),
    /** 使用：周期使用手持物品（放置方块 / 吃东西 / 钓鱼抛竿……） */
    USE("use", "使用"),
    /** 移动：朝面朝方向持续行走 */
    MOVE("move", "移动");

    private final String key;
    private final String display;

    BotAction(String key, String display) {
        this.key = key;
        this.display = display;
    }

    /** 命令里的子命令名 */
    public String key() {
        return key;
    }

    /** 给人看的中文名 */
    public String display() {
        return display;
    }

    /** 按键名找动作；找不到返回 null */
    public static BotAction byKey(String key) {
        if (key == null) return null;
        String lower = key.trim().toLowerCase();
        for (BotAction a : values()) {
            if (a.key.equals(lower)) return a;
        }
        return null;
    }
}
