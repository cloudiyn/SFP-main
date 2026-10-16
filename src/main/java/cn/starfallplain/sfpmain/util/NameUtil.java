package cn.starfallplain.sfpmain.util;

import java.util.regex.Pattern;

/**
 * 家 / 传送点名规则：**支持中文**、字母、数字、下划线，长度 1~16。
 * <p>
 * 刻意排除 {@code < > & §} 与空格等字符：
 * <ul>
 *   <li>{@code < >} 会被 MiniMessage 当成标签解析（名字里写 {@code <red>} 会污染显示）；</li>
 *   <li>{@code & §} 是传统色码；</li>
 *   <li>空格会让命令参数被拆开、也让列表显示混乱。</li>
 * </ul>
 * 长度按字符数（中文一个字算一个），与 Minecraft 玩家名规则的长度上限保持一致。
 */
public final class NameUtil {

    /** 汉字（\p{IsHan}）+ 字母数字下划线 */
    private static final Pattern NAME_PATTERN = Pattern.compile("^[\\p{IsHan}A-Za-z0-9_]{1,16}$");

    private NameUtil() {
    }

    public static boolean isValidName(String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }
}
