package cn.starfallplain.sfpmain.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;

/**
 * 统一消息管理：所有面向玩家的文案集中在 messages.yml。
 * <p>
 * 取值顺序：messages.yml 中对应键 → 代码内兜底文案。
 * 文案同时支持 MiniMessage 标签（&lt;red&gt;）与传统色码（§a / &amp;a），
 * 传统色码会被自动转换为 MiniMessage 标签，兼容旧配置文件。
 */
public final class Messages {

    private static final MiniMessage MM = MiniMessage.miniMessage();

    private final JavaPlugin plugin;
    private FileConfiguration config;
    private FileConfiguration fallback;

    public Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public void reload() {
        String fileName = "messages.yml";
        java.io.File file = new java.io.File(plugin.getDataFolder(), fileName);
        if (!file.exists()) {
            try {
                plugin.saveResource(fileName, false);
            } catch (IllegalArgumentException ignored) {
                // jar 内无该资源时忽略
            }
        }
        this.config = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file);
        java.io.InputStream in = plugin.getResource(fileName);
        this.fallback = in != null
                ? org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(
                        new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8))
                : null;
    }

    /** 取原始字符串（未解析），找不到时返回 fallbackText */
    public String raw(String key, String fallbackText) {
        String value = config.getString(key);
        if (value == null && fallback != null) {
            value = fallback.getString(key);
        }
        return value != null ? value : fallbackText;
    }

    /**
     * 取字符串列表（未解析），保持原始文本。
     * 若配置里该项是单行字符串，也会被当作单元素列表返回。
     */
    public List<String> rawList(String key) {
        List<String> lines = config.getStringList(key);
        if (lines.isEmpty() && config.isString(key)) {
            lines = List.of(config.getString(key, ""));
        }
        if (lines.isEmpty() && fallback != null) {
            lines = fallback.getStringList(key);
            if (lines.isEmpty() && fallback.isString(key)) {
                lines = List.of(fallback.getString(key, ""));
            }
        }
        return lines;
    }

    /** 取字符串并解析为 Component */
    public Component component(String key, String fallbackText) {
        return deserialize(raw(key, fallbackText));
    }

    /**
     * 该键是否存在于**玩家磁盘上的** messages.yml 中（**不含** jar 内置兜底文件）。
     * <p>
     * 供 {@code /sfp test} 检查「老配置文件是否缺少新键」—— 只看磁盘才能查出来：
     * 如果连 jar 内置默认文件一起算，那么只要插件本身带了该键就永远返回 true，
     * 检查形同虚设（2.11.0 修）。注意 {@link #raw} 有兜底，光看取值分不出缺键。
     */
    public boolean has(String key) {
        return config != null && config.isSet(key);
    }

    /** 解析并发送给指定接收者（支持多行 list 与单行 string） */
    public void send(CommandSender sender, String key, String fallbackText, Map<String, String> placeholders) {
        String value = raw(key, fallbackText);
        sender.sendMessage(deserialize(apply(value, placeholders)));
    }

    /** 占位符替换：{key} → 值 */
    public static String apply(String text, Map<String, String> placeholders) {
        if (text == null || placeholders == null || placeholders.isEmpty()) return text;
        String result = text;
        for (Map.Entry<String, String> e : placeholders.entrySet()) {
            result = result.replace("{" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
        }
        return result;
    }

    /** 解析文本：先转换传统色码，再按 MiniMessage 解析 */
    public static Component deserialize(String text) {
        if (text == null) return Component.empty();
        return MM.deserialize(convertLegacy(text));
    }

    /**
     * 将传统色码（§a、&amp;a）转换为 MiniMessage 标签。
     * 已经是 MiniMessage 标签的文本不受影响。
     */
    public static String convertLegacy(String text) {
        if (text == null || text.isEmpty()) return text;
        String s = text.replace('§', '&');
        if (s.indexOf('&') < 0) return s;
        StringBuilder sb = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char code = Character.toLowerCase(s.charAt(i + 1));
                String tag = switch (code) {
                    case '0' -> "<black>";
                    case '1' -> "<dark_blue>";
                    case '2' -> "<dark_green>";
                    case '3' -> "<dark_aqua>";
                    case '4' -> "<dark_red>";
                    case '5' -> "<dark_purple>";
                    case '6' -> "<gold>";
                    case '7' -> "<gray>";
                    case '8' -> "<dark_gray>";
                    case '9' -> "<blue>";
                    case 'a' -> "<green>";
                    case 'b' -> "<aqua>";
                    case 'c' -> "<red>";
                    case 'd' -> "<light_purple>";
                    case 'e' -> "<yellow>";
                    case 'f' -> "<white>";
                    case 'k' -> "<obfuscated>";
                    case 'l' -> "<bold>";
                    case 'm' -> "<strikethrough>";
                    case 'n' -> "<underlined>";
                    case 'o' -> "<italic>";
                    case 'r' -> "<reset>";
                    default -> null;
                };
                if (tag != null) {
                    sb.append(tag);
                    i++;
                    continue;
                }
            }
            sb.append(c);
        }
        return sb.toString();
    }
}
