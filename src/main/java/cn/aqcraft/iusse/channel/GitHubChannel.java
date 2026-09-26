package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.net.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.GitHubAppAuth;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.net.Http;

/**
 * GitHub 通道：在目标仓库创建 Issue。
 * <p>
 * 默认使用 **GitHub App**（机器人身份，token 自动轮换），
 * 也保留一条 PAT 的应急路径（{@code auth-type: token}）。
 */
public class GitHubChannel implements Channel {

    public static final String ID = "github";

    private final PluginConfig config;
    private final String userAgent;
    private final Proxy proxy;
    private final GitHubAppAuth appAuth;
    private final String disabledReason;

    public GitHubChannel(PluginConfig config, String userAgent) {
        this.config = config;
        this.userAgent = userAgent;
        this.proxy = config.resolveProxy();

        GitHubAppAuth auth = null;
        String reason = null;

        if (!config.isGitHubEnabled()) {
            reason = "配置中未启用";
        } else if (!config.isRepoConfigured()) {
            reason = "github.owner / github.repo 未配置";
        } else if (config.isGitHubUsingApp()) {
            try {
                auth = new GitHubAppAuth(config.getApiBase(),
                        config.getGitHubAppId(),
                        config.getGitHubInstallationId(),
                        config.getGitHubPrivateKey(),
                        config.getTimeoutMillis(),
                        proxy,
                        userAgent);
            } catch (Exception e) {
                reason = "App 私钥不可用：" + e.getMessage();
            }
        }
        this.appAuth = auth;
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
        String credential = resolveCredential();

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", submission.getTitle());
        payload.put("body", submission.toMarkdown());
        List<String> labels = submission.getLabels();
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }

        String url = config.getApiBase() + "/repos/" + config.getOwner() + "/" + config.getRepo() + "/issues";
        Http.Response response = Http.postJson(url, authHeaders(credential),
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
        String credential = resolveCredential();
        String url = config.getApiBase() + "/repos/" + config.getOwner() + "/" + config.getRepo();
        Http.Response response = Http.get(url, authHeaders(credential), config.getTimeoutMillis(), proxy);
        if (!response.isSuccess()) {
            throw new IOException(describeApiError(response));
        }
        String mode = config.isGitHubUsingApp() ? "GitHub App 机器人" : "PAT";
        return mode + " | 剩余限额 " + response.getRateRemaining();
    }

    /** 取当前可用的凭据：App 走 installation token，否则走配置里的 PAT。 */
    private String resolveCredential() throws IOException {
        if (config.isGitHubUsingApp()) {
            if (appAuth == null) {
                throw new IOException(getDisabledReason());
            }
            return appAuth.getToken();
        }
        if (!config.hasToken()) {
            throw new IOException("既未配置 GitHub App，也没有填写应急 PAT");
        }
        return config.getToken().trim();
    }

    private Map<String, String> authHeaders(String credential) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + credential);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", userAgent);
        return headers;
    }

    /** 供 /iusse status 展示机器人身份。 */
    public String describeAuth() {
        if (!config.isGitHubUsingApp()) {
            return config.hasToken() ? "PAT（应急模式）" : "未配置";
        }
        return appAuth == null ? "App 未就绪" : appAuth.describe();
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
                hint = "凭据无效，请检查 GitHub App 私钥或 installation-id";
                break;
            case 403:
                hint = "权限不足，或触发了 API 限流";
                break;
            case 404:
                hint = "仓库不存在，或 App 未被授予该仓库的访问权限";
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
