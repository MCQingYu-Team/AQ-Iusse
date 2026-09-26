package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.util.Map;

import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.GitHubApi;
import cn.aqcraft.iusse.github.MiniJson;

/**
 * GitHub 通道：用 PAT 在目标仓库创建 Issue。
 * <p>
 * 只需要一个 Fine-grained personal access token，权限给到目标仓库的
 * {@code Repository permissions → Issues: Read and write} 即可，
 * 不需要建 GitHub App、不需要私钥文件。
 * <p>
 * 实际的 HTTP 调用（含重试与错误翻译）都在 {@link GitHubApi} 里，
 * 这里只负责「把一条反馈变成一次 createIssue 调用」。
 */
public class GitHubChannel implements Channel {

    public static final String ID = "github";

    private final GitHubApi api;
    private final PluginConfig config;
    private final String disabledReason;

    public GitHubChannel(GitHubApi api, PluginConfig config) {
        this.api = api;
        this.config = config;

        String reason = null;
        if (!config.isGitHubEnabled()) {
            reason = "配置中未启用";
        } else if (!config.isRepoConfigured()) {
            reason = "github.owner / github.repo 未配置";
        } else if (!config.hasToken()) {
            reason = "未填写 channels.github.token（PAT）";
        }
        this.disabledReason = reason;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "GitHub";
    }

    @Override
    public boolean isEnabled() {
        return disabledReason == null;
    }

    @Override
    public String getDisabledReason() {
        return disabledReason == null ? "" : disabledReason;
    }

    @Override
    public void start() {
        // 无需常驻连接
    }

    @Override
    public void stop() {
        // 无需释放资源
    }

    @Override
    public ChannelResult submit(Submission submission) throws IOException {
        Map<String, Object> json = api.createIssue(
                submission.getTitle(), submission.toMarkdown(config.isRichIssueBody()),
                submission.getLabels());

        int number = MiniJson.integer(json, "number", 0);
        String issueUrl = MiniJson.string(json, "html_url");
        if (issueUrl == null || issueUrl.isEmpty()) {
            issueUrl = config.getRepoUrl() + "/issues/" + number;
        }
        return ChannelResult.success(this, "Issue #" + number, issueUrl);
    }

    @Override
    public String checkStatus() throws IOException {
        Map<String, Object> repo = api.getRepo();
        String fullName = MiniJson.string(repo, "full_name");
        int remaining = api.getLastRateRemaining();
        return "PAT 认证通过 | " + (fullName == null || fullName.isEmpty() ? "" : fullName + " | ")
                + "剩余限额 " + (remaining < 0 ? "未知" : String.valueOf(remaining));
    }
}
