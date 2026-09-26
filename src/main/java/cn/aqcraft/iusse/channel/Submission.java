package cn.aqcraft.iusse.channel;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 一条待发送的反馈：与具体渠道无关的纯数据。
 */
public class Submission {

    private final String playerName;
    private final String playerUuid;
    private final String categoryId;
    private final String categoryName;
    private final List<String> labels;
    /** 已带标题前缀的标题。 */
    private final String title;
    private final String body;
    /** 形如 "Paper 26.2 (git-Paper-xxx)"，不需要时可为 null。 */
    private final String serverInfo;
    private final Date time;

    public Submission(String playerName, String playerUuid, String categoryId, String categoryName,
                      List<String> labels, String title, String body, String serverInfo, Date time) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.labels = labels == null ? Collections.<String>emptyList() : labels;
        this.title = title;
        this.body = body;
        this.serverInfo = serverInfo;
        this.time = time == null ? new Date() : time;
    }

    public String getPlayerName() {
        return playerName;
    }

    public String getPlayerUuid() {
        return playerUuid;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public String getCategoryName() {
        return categoryName;
    }

    public List<String> getLabels() {
        return labels;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public String getServerInfo() {
        return serverInfo;
    }

    public Date getTime() {
        return time;
    }

    /** 格式化后的提交时间。 */
    public String getFormattedTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(time);
    }

    /** ISO-8601 时间，Discord embed 的 timestamp 用它。 */
    public String getIsoTime() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(time);
    }

    /** GitHub Issue 用的 Markdown 正文。 */
    public String toMarkdown() {
        StringBuilder builder = new StringBuilder();
        if (playerName != null) {
            builder.append("- **提交玩家**：").append(playerName);
            if (playerUuid != null && !playerUuid.isEmpty()) {
                builder.append(" (`").append(playerUuid).append("`)");
            }
            builder.append('\n');
        }
        builder.append("- **反馈分类**：").append(categoryName)
                .append(" (`").append(categoryId).append("`)\n");
        if (serverInfo != null && !serverInfo.isEmpty()) {
            builder.append("- **服务端**：").append(serverInfo).append('\n');
        }
        builder.append("- **提交时间**：").append(getFormattedTime()).append("\n\n---\n\n");
        builder.append(body == null ? "" : body);
        return builder.toString();
    }

    /** Discord / QQ 用的纯文本正文。 */
    public String toPlainText() {
        StringBuilder builder = new StringBuilder();
        if (playerName != null) {
            builder.append("玩家：").append(playerName).append('\n');
        }
        builder.append("分类：").append(categoryName).append('\n');
        if (serverInfo != null && !serverInfo.isEmpty()) {
            builder.append("服务端：").append(serverInfo).append('\n');
        }
        builder.append("时间：").append(getFormattedTime()).append('\n');
        builder.append("——————\n");
        builder.append(body == null ? "" : body);
        return builder.toString();
    }
}
