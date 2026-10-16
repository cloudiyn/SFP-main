package cn.starfallplain.sfpmain.util;

import java.util.Locale;

/**
 * 时长解析工具：把 {@code 7d}、{@code 12h}、{@code 1d2h30m}、{@code 90s} 这类写法
 * 解析成毫秒数，并支持 {@code perm} / {@code permanent}（永久）。
 * <p>
 * 支持的字段：{@code w}（周）{@code d}（天）{@code h}（时）{@code m}（分）{@code s}（秒）。
 * 单位可组合、可不按顺序（{@code 30m1h} 也接受）；**同一单位重复出现会累加**
 * （{@code 1h1h} = 2 小时），并不是「取最后一次」。
 */
public final class DurationParser {

    /** 表示「永久」的哨兵值（与 {@code Punishment.PERMANENT} 一致） */
    public static final long PERMANENT = -1L;

    private DurationParser() {
    }

    /**
     * 解析时长。
     *
     * @param input 时长字符串，如 {@code 7d} / {@code 12h30m} / {@code perm}
     * @return 毫秒数；{@link #PERMANENT} 表示永久；{@code -2} 表示无法解析
     */
    public static long parse(String input) {
        if (input == null) return -2;
        String s = input.trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return -2;
        if (s.equals("perm") || s.equals("permanent") || s.equals("forever") || s.equals("无限")) {
            return PERMANENT;
        }

        long total = 0;
        boolean matched = false;
        int i = 0;
        while (i < s.length()) {
            // 读取数字段
            int start = i;
            while (i < s.length() && (Character.isDigit(s.charAt(i)) || s.charAt(i) == '.')) {
                i++;
            }
            if (start == i) return -2; // 不是数字开头
            double value;
            try {
                value = Double.parseDouble(s.substring(start, i));
            } catch (NumberFormatException e) {
                return -2;
            }
            if (value < 0) return -2;

            // 读取单位
            if (i >= s.length()) return -2; // 数字后面必须有单位
            char unit = s.charAt(i);
            i++;
            long millis = switch (unit) {
                case 'w' -> (long) (value * 7 * 24 * 3600_000L);
                case 'd' -> (long) (value * 24 * 3600_000L);
                case 'h' -> (long) (value * 3600_000L);
                case 'm' -> (long) (value * 60_000L);
                case 's' -> (long) (value * 1000L);
                default -> -1;
            };
            if (millis < 0) return -2;
            total += millis;
            matched = true;
        }
        return matched ? total : -2;
    }

    /** 是否可解析 */
    public static boolean isValid(String input) {
        return parse(input) != -2;
    }

    /**
     * 把毫秒格式化成中文时长，如「2天3小时」「45分钟」「30秒」「永久」。
     * 只保留最大的两个单位，避免过于冗长。
     */
    public static String format(long millis) {
        if (millis == PERMANENT) return "永久";
        if (millis <= 0) return "0秒";

        long seconds = millis / 1000;
        long days = seconds / 86400;
        long hours = (seconds % 86400) / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;

        if (days > 0) {
            return hours > 0 ? days + "天" + hours + "小时" : days + "天";
        }
        if (hours > 0) {
            return minutes > 0 ? hours + "小时" + minutes + "分钟" : hours + "小时";
        }
        if (minutes > 0) {
            return secs > 0 ? minutes + "分钟" + secs + "秒" : minutes + "分钟";
        }
        return secs + "秒";
    }
}
