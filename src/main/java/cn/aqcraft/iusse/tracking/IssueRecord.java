package cn.aqcraft.iusse.tracking;

import java.util.LinkedHashMap;
import java.util.Map;

import cn.aqcraft.iusse.github.MiniJson;

/**
 * 一条被跟踪的反馈，对应 GitHub 上的一个 Issue。
 * <p>
 * 字段刻意做成可变的：轮询时会把最新的评论数、关闭状态写回，
 * 这些状态需要落盘，重启后不能把已通知过的条目再通知一遍。
 */
public class IssueRecord {

    /** Issue 编号。 */
    public final int number;
    public final String url;
    public final String playerName;
    public final String playerUuid;
    public final String title;
    public final String categoryName;
    /** 优先级显示名；空串表示默认档位（没设优先级）。 */
    public String priorityName = "";
    /** 提交时间戳。 */
    public final long submittedAt;

    /** 上一次看到的评论数，用来发现新回复。 */
    public int commentCount;
    /** Issue 是否已关闭。 */
    public boolean closed;
    /** 关闭通知是否已发给玩家（避免重启后重复通知）。 */
    public boolean closeNotified;
    /** 检测到关闭的时间，用于统计平均处理时长；0 表示未知。 */
    public long closedAt;
    /** 上一次 SLA 提醒时间，0 表示还没提醒过。 */
    public long lastSlaNotice;

    public IssueRecord(int number, String url, String playerName, String playerUuid,
                       String title, String categoryName, long submittedAt) {
        this.number = number;
        this.url = url == null ? "" : url;
        this.playerName = playerName == null ? "" : playerName;
        this.playerUuid = playerUuid == null ? "" : playerUuid;
        this.title = title == null ? "" : title;
        this.categoryName = categoryName == null ? "" : categoryName;
        this.submittedAt = submittedAt;
    }

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<String, Object>();
        map.put("number", number);
        map.put("url", url);
        map.put("player-name", playerName);
        map.put("player-uuid", playerUuid);
        map.put("title", title);
        map.put("category", categoryName);
        map.put("priority", priorityName);
        map.put("submitted-at", submittedAt);
        map.put("comment-count", commentCount);
        map.put("closed", closed);
        map.put("close-notified", closeNotified);
        map.put("closed-at", closedAt);
        map.put("last-sla-notice", lastSlaNotice);
        return map;
    }

    /**
     * 从 JSON 对象还原。
     *
     * @return 缺少编号时返回 {@code null}
     */
    public static IssueRecord fromMap(Map<String, Object> map) {
        int number = MiniJson.integer(map, "number", 0);
        if (number <= 0) {
            return null;
        }
        IssueRecord record = new IssueRecord(
                number,
                MiniJson.string(map, "url"),
                MiniJson.string(map, "player-name"),
                MiniJson.string(map, "player-uuid"),
                MiniJson.string(map, "title"),
                MiniJson.string(map, "category"),
                MiniJson.longValue(map, "submitted-at", 0L));
        record.commentCount = MiniJson.integer(map, "comment-count", 0);
        String priority = MiniJson.string(map, "priority");
        record.priorityName = priority == null ? "" : priority;
        record.closed = Boolean.TRUE.equals(map.get("closed"));
        record.closeNotified = Boolean.TRUE.equals(map.get("close-notified"));
        record.closedAt = MiniJson.longValue(map, "closed-at", 0L);
        record.lastSlaNotice = MiniJson.longValue(map, "last-sla-notice", 0L);
        return record;
    }
}
