package cn.aqcraft.iusse.tracking;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.aqcraft.iusse.github.MiniJson;

/**
 * 一条投递失败、等待重发的反馈。
 * <p>
 * 与 {@link IssueRecord} 不同，这里存的是「还没送出去的内容」，因此要保存
 * 标题、正文与分类，重发时才能重建出一样的 {@code Submission}。
 */
public class QueuedFeedback {

    /** 本地唯一 id，仅供日志区分。 */
    public final String id;
    public final String playerUuid;
    public final String playerName;
    public final String categoryId;
    public final String categoryName;
    public final String title;
    public final String body;
    /** 入队时间。 */
    public final long createdAt;

    /** 已经尝试过几次。 */
    public int attempts;

    public QueuedFeedback(String id, String playerUuid, String playerName, String categoryId,
                          String categoryName, String title, String body, long createdAt) {
        this.id = id;
        this.playerUuid = playerUuid == null ? "" : playerUuid;
        this.playerName = playerName == null ? "" : playerName;
        this.categoryId = categoryId == null ? "" : categoryId;
        this.categoryName = categoryName == null ? "" : categoryName;
        this.title = title == null ? "" : title;
        this.body = body == null ? "" : body;
        this.createdAt = createdAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("id", id);
        map.put("player-uuid", playerUuid);
        map.put("player-name", playerName);
        map.put("category-id", categoryId);
        map.put("category", categoryName);
        map.put("title", title);
        map.put("body", body);
        map.put("created-at", createdAt);
        map.put("attempts", attempts);
        return map;
    }

    /**
     * 从 JSON 对象还原。
     *
     * @return 标题或分类缺失时返回 {@code null}
     */
    public static QueuedFeedback fromMap(Map<String, Object> map) {
        String title = MiniJson.string(map, "title");
        String categoryId = MiniJson.string(map, "category-id");
        if (title == null || title.isEmpty() || categoryId == null || categoryId.isEmpty()) {
            return null;
        }
        String id = MiniJson.string(map, "id");
        QueuedFeedback item = new QueuedFeedback(
                id == null || id.isEmpty() ? title : id,
                MiniJson.string(map, "player-uuid"),
                MiniJson.string(map, "player-name"),
                categoryId,
                MiniJson.string(map, "category"),
                title,
                MiniJson.string(map, "body"),
                MiniJson.longValue(map, "created-at", System.currentTimeMillis()));
        item.attempts = MiniJson.integer(map, "attempts", 0);
        return item;
    }
}
