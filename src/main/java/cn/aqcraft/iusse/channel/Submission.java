package cn.aqcraft.iusse.channel;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 一条待发送的反馈：与具体渠道无关的纯数据。
 * <p>
 * 各渠道按顺序投递，先成功的渠道（GitHub）会把产生的链接登记进来，
 * 后续渠道（Discord / QQ）就能把链接一并写进消息。
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
    /** 兜底链接：GitHub 未启用或失败时，用它让消息里仍然有可点的地址。 */
    private final String fallbackLink;

    /** 各渠道投递成功后登记的链接。 */
    private final List<String> links = new ArrayList<String>();

    public Submission(String playerName, String playerUuid, String categoryId, String categoryName,
                      List<String> labels, String title, String body, String serverInfo, Date time,
                      String fallbackLink) {
        this.playerName = playerName;
        this.playerUuid = playerUuid;
        this.categoryId = categoryId;
        this.categoryName = categoryName;
        this.labels = labels == null ? Collections.<String>emptyList() : labels;
        this.title = title;
        this.body = body;
        this.serverInfo = serverInfo;
        this.time = time == null ? new Date() : time;
        this.fallbackLink = fallbackLink;
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

    /** 登记一个链接，重复的会被忽略。 */
    public synchronized void addLink(String link) {
        if (link != null && !link.isEmpty() && !links.contains(link)) {
            links.add(link);
        }
    }

    /** 当前所有可用链接：优先渠道产生的，否则回退到仓库地址。 */
    public synchronized List<String> getLinks() {
        if (!links.isEmpty()) {
            return new ArrayList<String>(links);
        }
        if (fallbackLink != null && !fallbackLink.isEmpty()) {
            return Collections.singletonList(fallbackLink);
        }
        return Collections.emptyList();
    }

    /** 第一个可用链接，没有则返回 {@code null}。 */
    public String getPrimaryLink() {
        List<String> all = getLinks();
        return all.isEmpty() ? null : all.get(0);
    }

    /** 格式化后的提交时间。 */
    public String getFormattedTime() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(time);
    }

    /** ISO-8601 时间，Discord embed 的 timestamp 用它。 */
    public String getIsoTime() {
        return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'").format(time);
    }

    /** GitHub Issue 用的 Markdown 正文（富文本排版）。 */
    public String toMarkdown() {
        return toMarkdown(true);
    }

    /**
     * GitHub Issue 用的 Markdown 正文。
     * <p>
     * 富文本版刻意做成「维护者一眼能扫完」的样子：
     * 顶上一句提示告诉他可以直接在 Issue 里回复（玩家会收到通知），
     * 中间一张信息表，正文单独成段，技术细节收进折叠块 ——
     * 因为排查时真正要读的只有正文，UUID 与服务端版本属于「需要时才展开」。
     *
     * @param rich true 用表格 / 折叠块；false 退化成简单的项目符号列表
     */
    public String toMarkdown(boolean rich) {
        return rich ? richMarkdown() : plainMarkdown();
    }

    private String richMarkdown() {
        StringBuilder builder = new StringBuilder(512);

        builder.append("> [!NOTE]\n")
                .append("> 本条反馈由服务器内 `/iusse` 提交。**直接在本 Issue 下回复即可**，")
                .append("提交的玩家会收到通知。\n\n");

        builder.append("## 反馈信息\n\n");
        builder.append("| 项目 | 内容 |\n| --- | --- |\n");
        if (playerName != null && !playerName.isEmpty()) {
            builder.append("| 提交玩家 | **").append(cell(playerName)).append("** |\n");
        }
        builder.append("| 反馈分类 | ").append(cell(categoryName))
                .append(" `").append(cell(categoryId)).append("` |\n");
        builder.append("| 提交时间 | ").append(getFormattedTime()).append(" |\n\n");

        builder.append("## 详细内容\n\n");
        builder.append(body == null ? "" : body);
        builder.append("\n\n---\n\n");

        builder.append("<details>\n<summary>技术信息（排查时用得上）</summary>\n\n");
        if (serverInfo != null && !serverInfo.isEmpty()) {
            builder.append("- 服务端：`").append(serverInfo).append("`\n");
        }
        if (playerUuid != null && !playerUuid.isEmpty()) {
            builder.append("- 玩家 UUID：`").append(playerUuid).append("`\n");
        }
        builder.append("- 来源：服务器内 `/iusse`\n");
        builder.append("\n</details>\n");

        return builder.toString();
    }

    private String plainMarkdown() {
        StringBuilder builder = new StringBuilder(256);
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

    /** Markdown 表格单元格里的竖线会把列切断，转义掉。 */
    private static String cell(String raw) {
        return raw == null ? "" : raw.replace("|", "\\|").replace("\n", " ");
    }

    /** Discord / QQ 用的纯文本正文，末尾会带上已知链接。 */
    public String toPlainText() {
        return toPlainText(true);
    }

    /**
     * 纯文本正文。
     *
     * @param includeLinks 是否在末尾附上链接（QQ 需要自行控制总长度时可传 false）
     */
    public String toPlainText(boolean includeLinks) {
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

        if (includeLinks) {
            List<String> knownLinks = getLinks();
            if (!knownLinks.isEmpty()) {
                builder.append("\n\n");
                for (int index = 0; index < knownLinks.size(); index++) {
                    if (index > 0) {
                        builder.append('\n');
                    }
                    builder.append(knownLinks.get(index));
                }
            }
        }
        return builder.toString();
    }
}
