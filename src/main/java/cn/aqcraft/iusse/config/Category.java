package cn.aqcraft.iusse.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 一个反馈分类。
 * <p>
 * 既是对话框里「反馈分类」下拉框的一个选项，也是新 Issue 的标签来源。
 */
public class Category {

    private final String id;
    private final String name;
    private final String description;
    private final List<String> labels;
    /** 该分类的内容是否参与隐私打码（举报投诉通常需要保留 QQ 号，可以关掉）。 */
    private final boolean maskSensitive;

    public Category(String id, String name, String description, List<String> labels) {
        this(id, name, description, labels, true);
    }

    public Category(String id, String name, String description, List<String> labels, boolean maskSensitive) {
        this.id = id;
        this.name = name;
        this.description = description == null ? "" : description;
        this.labels = labels == null ? new ArrayList<String>() : labels;
        this.maskSensitive = maskSensitive;
    }

    /**
     * 从 config.yml 的 categories 列表项构建分类。
     *
     * @return 配置合法时返回分类对象，id 缺失时返回 {@code null}
     */
    @SuppressWarnings("unchecked")
    public static Category fromMap(Map<?, ?> map) {
        if (map == null) {
            return null;
        }
        Object idValue = map.get("id");
        if (idValue == null || String.valueOf(idValue).trim().isEmpty()) {
            return null;
        }
        String id = String.valueOf(idValue).trim().toLowerCase();
        String name = map.get("name") == null ? id : String.valueOf(map.get("name"));
        String description = map.get("description") == null ? "" : String.valueOf(map.get("description"));

        List<String> labels = new ArrayList<String>();
        if (map.get("labels") instanceof List) {
            for (Object element : (List<Object>) map.get("labels")) {
                if (element != null && !String.valueOf(element).trim().isEmpty()) {
                    labels.add(String.valueOf(element).trim());
                }
            }
        }
        // 不给默认值就是开启打码，需要原样保留内容的分类才显式写 mask: false
        boolean mask = !Boolean.FALSE.equals(map.get("mask"));
        return new Category(id, name, description, labels, mask);
    }

    /** 该分类的内容是否需要打码（还要叠加 config.yml 里的全局开关）。 */
    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    public String getId() {
        return id;
    }

    /** 下拉框中显示的名字。 */
    public String getName() {
        return name;
    }

    /** 一句话说明。 */
    public String getDescription() {
        return description;
    }

    /** 该分类额外附加的 GitHub 标签。 */
    public List<String> getLabels() {
        return Collections.unmodifiableList(labels);
    }

    @Override
    public String toString() {
        return "Category{" + id + "}";
    }
}
