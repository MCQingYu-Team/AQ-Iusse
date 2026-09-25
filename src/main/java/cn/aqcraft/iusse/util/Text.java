package cn.aqcraft.iusse.util;

import java.util.regex.Pattern;

/**
 * 文本处理工具：颜色代码翻译、长度裁剪等。
 * <p>
 * 不依赖 {@code org.bukkit.ChatColor}（Paper 已将其标记为过时），直接对 § 颜色代码做转写，
 * 客户端渲染结果完全一致。
 */
public final class Text {

    /** 合法的颜色 / 格式代码字符。 */
    private static final String COLOR_CODES = "0123456789AaBbCcDdEeFfKkLlMmNnOoRrXx";

    /** §（或 &）后跟一个颜色 / 格式代码。 */
    private static final Pattern COLOR_PATTERN =
            Pattern.compile("[\u00A7\u0026][0-9A-FK-ORXa-fk-orx]");

    private Text() {
    }

    /** 把 &amp; 颜色代码转换为客户端认得的形式。 */
    public static String color(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        char[] chars = raw.toCharArray();
        for (int index = 0; index < chars.length - 1; index++) {
            if (chars[index] == '&' && COLOR_CODES.indexOf(chars[index + 1]) >= 0) {
                chars[index] = '\u00A7';
            }
        }
        return new String(chars);
    }

    /** 去掉所有颜色代码，用于长度校验与对话框文本。 */
    public static String plain(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return COLOR_PATTERN.matcher(color(raw)).replaceAll("");
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
