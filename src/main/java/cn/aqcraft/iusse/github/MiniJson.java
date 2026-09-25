package cn.aqcraft.iusse.github;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 编解码器。
 * <p>
 * 插件刻意不引入任何第三方依赖（Gson / Jackson 在部分服务端不一定可见），
 * 而 GitHub REST API 只需要「发一个对象、读几个字段」，手写一份足够轻量可靠。
 *
 * <ul>
 *   <li>{@link #write(Object)} 支持 {@code Map / List / String / Number / Boolean / null}</li>
 *   <li>{@link #parse(String)} 得到 {@code Map / List / String / Double / Boolean / null}</li>
 * </ul>
 */
public final class MiniJson {

    private MiniJson() {
    }

    // ------------------------------------------------------------------
    // 编码
    // ------------------------------------------------------------------

    /** 序列化任意受支持的对象。 */
    public static String write(Object value) {
        StringBuilder sb = new StringBuilder(256);
        writeValue(value, sb);
        return sb.toString();
    }

    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            sb.append(quote((String) value));
        } else if (value instanceof Number || value instanceof Boolean) {
            sb.append(String.valueOf(value));
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                sb.append(quote(String.valueOf(entry.getKey()))).append(':');
                writeValue(entry.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object element : (Iterable<?>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(element, sb);
            }
            sb.append(']');
        } else {
            sb.append(quote(String.valueOf(value)));
        }
    }

    /** 把字符串转义并加上引号。 */
    public static String quote(String raw) {
        if (raw == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(raw.length() + 16);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                case '\b':
                    sb.append("\\b");
                    break;
                case '\f':
                    sb.append("\\f");
                    break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                    break;
            }
        }
        sb.append('"');
        return sb.toString();
    }

    // ------------------------------------------------------------------
    // 解码
    // ------------------------------------------------------------------

    public static class JsonException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public JsonException(String message) {
            super(message);
        }
    }

    /** 解析 JSON 文本。 */
    public static Object parse(String json) {
        if (json == null) {
            throw new JsonException("空响应");
        }
        Parser parser = new Parser(json);
        parser.skipWhitespace();
        Object value = parser.readValue();
        parser.skipWhitespace();
        return value;
    }

    /** 解析 JSON 对象；若不是对象则抛出异常。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        Object value = parse(json);
        if (!(value instanceof Map)) {
            throw new JsonException("响应不是 JSON 对象");
        }
        return (Map<String, Object>) value;
    }

    /** 安全地从对象里取字符串字段。 */
    public static String string(Map<String, Object> map, String key) {
        if (map == null) {
            return null;
        }
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    /** 安全地从对象里取整数字段。 */
    public static int integer(Map<String, Object> map, String key, int fallback) {
        if (map == null) {
            return fallback;
        }
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private static final class Parser {

        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        void skipWhitespace() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (index >= text.length()) {
                throw new JsonException("JSON 意外结束");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            index++; // '{'
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                String key = readString();
                skipWhitespace();
                if (index >= text.length() || text.charAt(index) != ':') {
                    throw new JsonException("JSON 对象缺少 ':'");
                }
                index++;
                map.put(key, readValue());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new JsonException("JSON 对象未闭合");
                }
                char c = text.charAt(index++);
                if (c == '}') {
                    return map;
                }
                if (c != ',') {
                    throw new JsonException("JSON 对象缺少 ','");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<Object>();
            index++; // '['
            skipWhitespace();
            if (index < text.length() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (index >= text.length()) {
                    throw new JsonException("JSON 数组未闭合");
                }
                char c = text.charAt(index++);
                if (c == ']') {
                    return list;
                }
                if (c != ',') {
                    throw new JsonException("JSON 数组缺少 ','");
                }
            }
        }

        private String readString() {
            if (index >= text.length() || text.charAt(index) != '"') {
                throw new JsonException("JSON 期望字符串");
            }
            index++;
            StringBuilder sb = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    break;
                }
                char esc = text.charAt(index++);
                switch (esc) {
                    case '"':
                        sb.append('"');
                        break;
                    case '\\':
                        sb.append('\\');
                        break;
                    case '/':
                        sb.append('/');
                        break;
                    case 'b':
                        sb.append('\b');
                        break;
                    case 'f':
                        sb.append('\f');
                        break;
                    case 'n':
                        sb.append('\n');
                        break;
                    case 'r':
                        sb.append('\r');
                        break;
                    case 't':
                        sb.append('\t');
                        break;
                    case 'u':
                        if (index + 4 > text.length()) {
                            throw new JsonException("JSON \\u 转义不完整");
                        }
                        sb.append((char) Integer.parseInt(text.substring(index, index + 4), 16));
                        index += 4;
                        break;
                    default:
                        sb.append(esc);
                        break;
                }
            }
            throw new JsonException("JSON 字符串未闭合");
        }

        private Object readNumber() {
            int start = index;
            while (index < text.length()) {
                char c = text.charAt(index);
                if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E' || (c >= '0' && c <= '9')) {
                    index++;
                } else {
                    break;
                }
            }
            String raw = text.substring(start, index);
            if (raw.isEmpty()) {
                throw new JsonException("JSON 数值格式错误");
            }
            if (raw.indexOf('.') < 0 && raw.indexOf('e') < 0 && raw.indexOf('E') < 0) {
                try {
                    return Long.valueOf(raw);
                } catch (NumberFormatException ignored) {
                    // 超出 long 范围时退化为 double
                }
            }
            return Double.valueOf(raw);
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new JsonException("JSON 期望 " + literal);
            }
            index += literal.length();
        }
    }
}
