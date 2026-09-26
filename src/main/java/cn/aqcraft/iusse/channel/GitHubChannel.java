package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.net.Http;

/**
 * GitHub 通道：用 PAT 在目标仓库创建 Issue。
 * <p>
 * 只需要一个 Fine-grained personal access token，权限给到目标仓库的
 * {@code Repository permissions → Issues: Read and write} 即可，
 * 不需要建 GitHub App、不需要私钥文件。
 */
public class GitHubChannel implements Channel {

    public static final String ID = "github";

    private final PluginConfig config;
    private final String userAgent;
    private final Proxy proxy;
    private final String disabledReason;

    public GitHubChannel(PluginConfig config, String userAgent) {
        this.config = config;
        this.userAgent = userAgent;
        this.proxy = config.resolveProxy();

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
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", submission.getTitle());
        payload.put("body", submission.toMarkdown());
        List<String> labels = submission.getLabels();
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }

        String url = config.getApiBase() + "/repos/" + config.getOwner() + "/" + config.getRepo() + "/issues";
        Http.Response response = Http.postJson(url, authHeaders(),
                MiniJson.write(payload), config.getTimeoutMillis(), proxy);

        if (!response.isSuccess()) {
            throw new IOException(describeApiError(response));
        }
        try {
            Map<String, Object> json = MiniJson.parseObject(response.getBody());
            int number = MiniJson.integer(json, "number", 0);
            String issueUrl = MiniJson.string(json, "html_url");
            if (issueUrl == null) {
                issueUrl = config.getRepoUrl() + "/issues/" + number;
            }
            return ChannelResult.success(this, "Issue #" + number, issueUrl);
        } catch (RuntimeException e) {
            return ChannelResult.success(this, "已创建，但无法解析响应");
        }
    }

    @Override
    public String checkStatus() throws IOException {
        String url = config.getApiBase() + "/repos/" + config.getOwner() + "/" + config.getRepo();
        Http.Response response = Http.get(url, authHeaders(), config.getTimeoutMillis(), proxy);
        if (!response.isSuccess()) {
            throw new IOException(describeApiError(response));
        }
        return "PAT 认证通过 | 剩余限额 " + response.getRateRemaining();
    }

    private Map<String, String> authHeaders() {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + config.getToken().trim());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", userAgent);
        return headers;
    }

    private String describeApiError(Http.Response response) {
        String detail = null;
        try {
            detail = MiniJson.string(MiniJson.parseObject(response.getBody()), "message");
        } catch (RuntimeException ignored) {
            // 保留原始响应作兜底
        }
        String hint;
        switch (response.getCode()) {
            case 401:
                hint = "PAT 无效或已过期，请重新生成并更新 channels.github.token";
                break;
            case 403:
                hint = "0".equals(response.getRateRemaining())
                        ? "API 访问次数已用尽，请稍后再试"
                        : "权限不足，请确认 PAT 拥有该仓库的 Issues: Read and write";
                break;
            case 404:
                hint = "仓库不存在，或 PAT 未被授权访问该仓库";
                break;
            case 410:
                hint = "该仓库已关闭 Issues 功能";
                break;
            case 422:
                hint = "内容校验失败（可能是标签不存在或标题重复）";
                break;
            default:
                hint = response.getCode() >= 500 ? "GitHub 服务端异常，请稍后再试" : "请求被 GitHub 拒绝";
                break;
        }
        String message = hint + "（HTTP " + response.getCode() + "）";
        return detail == null || detail.isEmpty() ? message : message + "：" + detail;
    }
}
