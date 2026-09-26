package cn.aqcraft.iusse.util;

import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 隐私信息打码。
 * <p>
 * 反馈正文最终会落到 GitHub 仓库（通常是公开的）与 QQ 群，
 * 玩家却常常顺手把「我的 IP 是 1.2.3.4」「QQ 12345678」一起写进去。
 * 这里在投递前把常见的一眼就能认出来的隐私信息打上码，
 * 既能保住排查问题需要的上下文（前几位仍然可见），又不会把详细信息公开出去。
 * <p>
 * 只处理高置信度的形态，宁可漏掉也不误伤：
 * <ul>
 *   <li>IPv4：每一段都必须 ≤ 255，避免把 {@code 1.2.3.4.5} 这类版本号当 IP</li>
 *   <li>手机号：只认 1[3-9] 开头的 11 位</li>
 *   <li>邮箱：只保留首字母与域名</li>
 *   <li>QQ：只在明确写了「QQ / 扣扣 / 企鹅 / QQ 群」时才打码，避免把普通数字误伤</li>
 * </ul>
 */
public final class Sanitizer {

    /** 点分十进制 IPv4。 */
    private static final Pattern IPV4 =
            Pattern.compile("\\b(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\b");

    /** 中国大陆手机号。 */
    private static final Pattern PHONE =
            Pattern.compile("(?<!\\d)(1[3-9]\\d)\\d{4}(\\d{4})(?!\\d)");

    /** 明确标注了 QQ 的号码（含 QQ 群），长写法放在前面，否则「QQ群号」会被「群」抢先匹配。 */
    private static final Pattern QQ =
            Pattern.compile("(?i)((?:qq|扣扣|企鹅)\\s*(?:群号|号码|群|号|是|为)?\\s*[:：=]?\\s*)(\\d{5,12})");

    /** 邮箱地址。 */
    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");

    private Sanitizer() {
    }

    /** 打码结果。 */
    public static final class Result {

        private final String text;
        private final boolean masked;

        Result(String text, boolean masked) {
            this.text = text;
            this.masked = masked;
        }

        /** 处理后的文本；未启用打码时原样返回。 */
        public String getText() {
            return text;
        }

        /** 是否真的改动了内容（用来决定要不要提示玩家）。 */
        public boolean isMasked() {
            return masked;
        }
    }

    /** 按默认启用打码。 */
    public static Result mask(String raw) {
        return mask(raw, true);
    }

    /**
     * 对文本做隐私打码。
     *
     * @param enabled 为 false 时原样返回，且 {@link Result#isMasked()} 恒为 false
     */
    public static Result mask(String raw, boolean enabled) {
        if (raw == null || raw.isEmpty() || !enabled) {
            return new Result(raw == null ? "" : raw, false);
        }

        boolean[] changed = new boolean[1];

        String text = replace(raw, IPV4, matcher -> {
            if (!isValidIpv4(matcher.group(1), matcher.group(2), matcher.group(3), matcher.group(4))) {
                return matcher.group();
            }
            changed[0] = true;
            return matcher.group(1) + "." + matcher.group(2) + ".*.*";
        });

        text = replace(text, PHONE, matcher -> {
            changed[0] = true;
            return matcher.group(1) + "****" + matcher.group(2);
        });

        text = replace(text, EMAIL, matcher -> {
            changed[0] = true;
            return maskEmail(matcher.group());
        });

        text = replace(text, QQ, matcher -> {
            changed[0] = true;
            return matcher.group(1) + maskMiddle(matcher.group(2));
        });

        return new Result(text, changed[0]);
    }

    /** 文本里是否含有疑似隐私信息（只判断、不改动）。 */
    public static boolean containsSensitive(String raw) {
        if (raw == null || raw.isEmpty()) {
            return false;
        }
        return IPV4.matcher(raw).find() || PHONE.matcher(raw).find()
                || EMAIL.matcher(raw).find() || QQ.matcher(raw).find();
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private static String replace(String text, Pattern pattern, Function<Matcher, String> replacer) {
        Matcher matcher = pattern.matcher(text);
        StringBuffer builder = new StringBuffer(text.length() + 16);
        while (matcher.find()) {
            matcher.appendReplacement(builder, Matcher.quoteReplacement(replacer.apply(matcher)));
        }
        matcher.appendTail(builder);
        return builder.toString();
    }

    private static boolean isValidIpv4(String a, String b, String c, String d) {
        return inRange(a) && inRange(b) && inRange(c) && inRange(d);
    }

    private static boolean inRange(String raw) {
        try {
            int value = Integer.parseInt(raw);
            return value >= 0 && value <= 255;
        } catch (NumberFormatException e) {
            return false;
        }
    }

    /** 邮箱只留首字母与域名：{@code zhangsan@qq.com} → {@code z***@qq.com}。 */
    private static String maskEmail(String email) {
        int at = email.indexOf('@');
        if (at <= 0) {
            return "***";
        }
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** 保留前 2 位与最后 1 位：{@code 12345678} → {@code 12*****8}。 */
    private static String maskMiddle(String digits) {
        if (digits.length() <= 4) {
            return "****";
        }
        StringBuilder builder = new StringBuilder(digits.length());
        builder.append(digits, 0, 2);
        for (int index = 2; index < digits.length() - 1; index++) {
            builder.append('*');
        }
        builder.append(digits.charAt(digits.length() - 1));
        return builder.toString();
    }
}
