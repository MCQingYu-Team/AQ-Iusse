package cn.aqcraft.iusse.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 一个优先级档位。
 * <p>
 * GitHub Issue **没有**内建的 Priority 字段，所以这里用标签实现：
 * 每个档位对应一个（可选的）GitHub 标签，投递时挂到 Issue 上。
 * 「普通」这类档位的 {@code label} 留空，表示不加任何标签 —— 也就是「没有优先级」，
 * 这样默认提交的 Issue 不会多出一个没意义的标签。
 *
 * <p>用标签而不是 Projects v2 的 Priority 字段，是因为后者要走 GraphQL、
 * 要额外的 Projects 权限、还得知道项目编号；标签方案复用现有投递路径，失败面小得多。
 */
public class Priority {

    private final String id;
    private final String name;
    /** 该档位对应的 GitHub 标签；空串表示不加标签。 */
    private final String label;
    /** 是否作为表单里的默认选中项。 */
    private final boolean isDefault;

    public Priority(String id, String name, String label, boolean isDefault) {
        this.id = id;
        this.name = name == null || name.isEmpty() ? id : name;
        this.label = label == null ? "" : label.trim();
        this.isDefault = isDefault;
    }

    /**
     * 从 config.yml 的 priorities 列表项构建。
     *
     * @return id 缺失时返回 {@code null}
     */
    public static Priority fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Object idValue = map.get("id");
        if (idValue == null || String.valueOf(idValue).trim().isEmpty()) {
            return null;
        }
        String id = String.valueOf(idValue).trim().toLowerCase(Locale.ROOT);
        String name = map.get("name") == null ? id : String.valueOf(map.get("name"));
        String label = map.get("label") == null ? "" : String.valueOf(map.get("label"));
        return new Priority(id, name, label, Boolean.TRUE.equals(map.get("default")));
    }

    /** 批量解析，忽略不合法的条目。 */
    public static List<Priority> parse(List<Map<?, ?>> maps) {
        if (maps == null || maps.isEmpty()) {
            return Collections.emptyList();
        }
        List<Priority> result = new ArrayList<Priority>(maps.size());
        for (Map<?, ?> map : maps) {
            Priority priority = fromMap(map);
            if (priority != null) {
                result.add(priority);
            }
        }
        return result;
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    /** 对应的 GitHub 标签名；空串表示该档位不加标签。 */
    public String getLabel() {
        return label;
    }

    public boolean isDefault() {
        return isDefault;
    }

    /** 是否要往 Issue 上挂标签。 */
    public boolean hasLabel() {
        return !label.isEmpty();
    }

    /** id 或显示名都算匹配 —— 让 {@code /iusse priority 12 高} 这种写法也能用。 */
    public boolean matches(String raw) {
        if (raw == null) {
            return false;
        }
        String value = raw.trim();
        if (value.isEmpty()) {
            // 空串代表「没选」，不是要匹配某个档位
            return false;
        }
        return id.equalsIgnoreCase(value) || name.equalsIgnoreCase(value);
    }
}
