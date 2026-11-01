package cn.starfallplain.sfpmain.bot;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 假人设置项（对齐 minecraft-fakeplayer 的 personal configuration）。
 * <p>
 * 每个假人一份（{@code bots.<名字>.settings}），每位玩家还有一份**个人默认配置**
 * （{@code player-configs.<uuid>}，下次创建假人时套用）。
 * <p>
 * 全部为布尔开关；{@link #DEFAULTS} 是没有任何配置时的初始值
 * （{@code bot.yml} 的 {@code defaults} 段可覆盖，见 {@code BotConfig}）。
 */
public enum BotSettings {

    /** 碰撞盒：false 时其他玩家可以穿过假人 */
    COLLIDABLE("collidable", "碰撞盒"),
    /** 无敌：false 时假人会受伤、挨饿（需要食物与回血维持） */
    INVULNERABLE("invulnerable", "无敌"),
    /** 超级回复（狼叔模式）：定时把生命与饱食度回满 */
    WOLVERINE("wolverine", "超级回复"),
    /** 自动看向附近的可攻击实体（配合 attack 自动打怪） */
    LOOK_AT_ENTITY("look_at_entity", "自动看向实体"),
    /** 自动捡起掉落物 */
    PICKUP_ITEMS("pickup_items", "自动捡物品"),
    /** 自动补充快捷栏：快捷栏空位自动从背包补货 */
    REPLENISH("replenish", "自动补充快捷栏"),
    /** 自动钓鱼：手持鱼竿时自动抛竿、等咬钩、收竿 */
    AUTOFISH("autofish", "自动钓鱼"),
    /** 创建时使用创建者自己的皮肤 */
    SKIN("skin", "使用创建者皮肤");

    /** 初始默认值（bot.yml 的 defaults 段未配置时使用） */
    public static final Map<BotSettings, Boolean> DEFAULTS = new LinkedHashMap<>();

    static {
        DEFAULTS.put(COLLIDABLE, true);
        DEFAULTS.put(INVULNERABLE, true);
        DEFAULTS.put(WOLVERINE, false);
        DEFAULTS.put(LOOK_AT_ENTITY, false);
        DEFAULTS.put(PICKUP_ITEMS, true);
        DEFAULTS.put(REPLENISH, false);
        DEFAULTS.put(AUTOFISH, false);
        DEFAULTS.put(SKIN, true);
    }

    private final String key;
    private final String display;

    BotSettings(String key, String display) {
        this.key = key;
        this.display = display;
    }

    /** bot.yml / 命令里使用的键名（小写连字符风格） */
    public String key() {
        return key;
    }

    /** 给人看的中文名 */
    public String display() {
        return display;
    }

    /** 按键名找设置项；找不到返回 null */
    public static BotSettings byKey(String key) {
        if (key == null) return null;
        String lower = key.trim().toLowerCase().replace('-', '_');
        for (BotSettings s : values()) {
            if (s.key.equals(lower)) return s;
        }
        return null;
    }

    /**
     * 解析布尔值：true/false/on/off/1/0/是/否。
     *
     * @return 解析结果；无法识别时返回 null
     */
    public static Boolean parseBoolean(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase()) {
            case "true", "on", "1", "yes", "是", "开", "开启" -> true;
            case "false", "off", "0", "no", "否", "关", "关闭" -> false;
            default -> null;
        };
    }

    /** 把布尔值格式化成命令回显用的文本 */
    public static String format(Boolean value) {
        return value == null ? "-" : (value ? "开启" : "关闭");
    }
}
