package cn.aqcraft.iusse.util;

import java.util.HashMap;
import java.util.Map;

/**
 * 标题相似度判断（用于「这条反馈是不是已经有人提过了」）。
 * <p>
 * 用字符二元组（bigram）的 Dice 系数：
 * 中文没有词边界，按字符切比按词切更稳；短标题的噪声用
 * 「互相包含」这条补充规则兜住（例如「传送门附近崩溃」与「下界传送门附近崩溃」）。
 */
public final class Similarity {

    /** 短于这个长度的标题不参与「互相包含」判断，否则「卡顿」会命中一切。 */
    private static final int MIN_CONTAINMENT_LENGTH = 6;

    private Similarity() {
    }

    /** 归一化：只保留字母、数字与汉字，并统一小写。 */
    public static String normalize(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (Character.isLetterOrDigit(c)) {
                builder.append(Character.toLowerCase(c));
            }
        }
        return builder.toString();
    }

    /** 两个标题是否算重复。 */
    public static boolean isSimilar(String a, String b, double threshold) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (a.equals(b)) {
            return true;
        }
        if (a.length() >= MIN_CONTAINMENT_LENGTH && b.length() >= MIN_CONTAINMENT_LENGTH
                && (a.contains(b) || b.contains(a))) {
            return true;
        }
        return dice(a, b) >= threshold;
    }

    /**
     * 字符二元组的 Dice 系数。
     *
     * @return 0.0 ~ 1.0，完全相同为 1.0
     */
    public static double dice(String a, String b) {
        if (a == null || b == null) {
            return 0.0;
        }
        if (a.equals(b)) {
            return 1.0;
        }
        if (a.length() < 2 || b.length() < 2) {
            return 0.0;
        }

        Map<String, Integer> left = bigrams(a);
        Map<String, Integer> right = bigrams(b);

        int total = 0;
        int intersection = 0;
        for (Map.Entry<String, Integer> entry : left.entrySet()) {
            total += entry.getValue();
            Integer other = right.get(entry.getKey());
            if (other != null) {
                intersection += Math.min(entry.getValue(), other);
            }
        }
        for (Integer count : right.values()) {
            total += count;
        }
        return total == 0 ? 0.0 : (2.0 * intersection) / total;
    }

    private static Map<String, Integer> bigrams(String text) {
        Map<String, Integer> map = new HashMap<String, Integer>(text.length() * 2);
        for (int index = 0; index + 1 < text.length(); index++) {
            String gram = text.substring(index, index + 2);
            Integer previous = map.get(gram);
            map.put(gram, previous == null ? 1 : previous + 1);
        }
        return map;
    }
}
