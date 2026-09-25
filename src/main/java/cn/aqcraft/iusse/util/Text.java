package cn.aqcraft.iusse.util;

import org.bukkit.ChatColor;

/**
 * 文本处理工具：颜色代码翻译、长度裁剪等。
 */
public final class Text {

    private Text() {
    }

    /** 把 &amp; 颜色代码转换为原版颜色代码。 */
    public static String color(String raw) {
        return raw == null ? "" : ChatColor.translateAlternateColorCodes('&', raw);
    }

    /** 去掉所有颜色代码，用于长度校验。 */
    public static String plain(String raw) {
        return raw == null ? "" : ChatColor.stripColor(color(raw));
    }

    /** 把多行文本合并成一行（用于标题等单行场景）。 */
    public static String oneLine(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 安全截断，避免超过长度限制。 */
    public static String truncate(String raw, int max) {
        if (raw == null) {
            return "";
        }
        if (raw.length() <= max) {
            return raw;
        }
        return raw.substring(0, max) + "…";
    }

    /** 占位符替换：{key} -> value。 */
    public static String replace(String template, String key, Object value) {
        if (template == null) {
            return "";
        }
        return template.replace("{" + key + "}", String.valueOf(value));
    }
}
