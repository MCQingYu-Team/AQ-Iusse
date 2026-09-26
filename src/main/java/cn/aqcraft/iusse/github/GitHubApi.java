package cn.aqcraft.iusse.github;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.net.Http;

/**
 * GitHub REST API 客户端（全插件共用一份）。
 * <p>
 * 早先只有 {@code GitHubChannel} 会调 GitHub，逻辑散在通道里就够了；
 * 现在跟踪器、重复检测、{@code /iusse close}、自检都要用，
 * 于是统一收拢到这里，顺带把三件所有调用方都需要的事一次做掉：
 *
 * <ul>
 *   <li><b>失败重试</b>：网络抖动、GitHub 5xx / 429 自动重试（1s、4s、9s…），4xx 不重试</li>
 *   <li><b>额度观测</b>：记录每次响应的 {@code X-RateLimit-Remaining}，供轮询自动降频</li>
 *   <li><b>错误翻译</b>：401/403/404/410/422 都翻译成能照着做的中文提示</li>
 * </ul>
 *
 * 所有方法都会阻塞，必须在异步线程调用。
 */
public class GitHubApi {

    /** 一条 Issue 的摘要，够用且不必解析整个响应。 */
    public static final class IssueSummary {

        public final int number;
        public final String title;
        public final String url;
        /** {@code open} 或 {@code closed}。 */
        public final String state;
        /** 评论数（不含正文）。 */
        public final int comments;

        public IssueSummary(int number, String title, String url, String state, int comments) {
            this.number = number;
            this.title = title == null ? "" : title;
            this.url = url == null ? "" : url;
            this.state = state == null ? "" : state;
            this.comments = comments;
        }

        public boolean isClosed() {
            return "closed".equalsIgnoreCase(state);
        }
    }

    /** 重试等待基数：第 n 次重试等 n²×1s。 */
    private static final long RETRY_BASE_MILLIS = 1000L;
    /** 单次重试等待上限。 */
    private static final long RETRY_MAX_MILLIS = 15000L;

    private final AqIssuePlugin plugin;

    /** 最近一次响应里的剩余额度，-1 表示还没拿到过。 */
    private volatile int lastRateRemaining = -1;

    public GitHubApi(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    /** 配置是否完整到可以调 API。 */
    public boolean isAvailable() {
        PluginConfig config = plugin.getPluginConfig();
        return config.isGitHubEnabled() && config.hasToken() && config.isRepoConfigured();
    }

    /** 最近一次拿到的 GitHub API 剩余额度，-1 表示未知。 */
    public int getLastRateRemaining() {
        return lastRateRemaining;
    }

    // ------------------------------------------------------------------
    // 业务方法
    // ------------------------------------------------------------------

    /** 读仓库信息（自检用）。 */
    public Map<String, Object> getRepo() throws IOException {
        return object(request("GET", "/repos/" + repoPath(), null));
    }

    /** 列出仓库里已有的标签名（自检时和配置里的标签对比）。 */
    public List<String> listLabels() throws IOException {
        List<Object> array = array(request("GET", "/repos/" + repoPath() + "/labels?per_page=100", null));
        List<String> names = new ArrayList<String>(array.size());
        for (Object element : array) {
            if (!(element instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) element;
            String name = MiniJson.string(map, "name");
            if (name != null && !name.isEmpty()) {
                names.add(name);
            }
        }
        return names;
    }

    /** 查一条 Issue。 */
    public IssueSummary getIssue(int number) throws IOException {
        return toSummary(object(request("GET", "/repos/" + repoPath() + "/issues/" + number, null)));
    }

    /**
     * 列出最近的 Issue（重复检测用）。
     *
     * @param state {@code open} / {@code closed} / {@code all}
     */
    public List<IssueSummary> listIssues(String state, int limit) throws IOException {
        int size = Math.max(1, Math.min(100, limit));
        String path = "/repos/" + repoPath() + "/issues?state=" + state
                + "&sort=created&direction=desc&per_page=" + size;
        List<Object> array = array(request("GET", path, null));
        List<IssueSummary> result = new ArrayList<IssueSummary>(array.size());
        for (Object element : array) {
            if (!(element instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) element;
            // 列表接口会把 PR 也当成 issue 返回，用 pull_request 字段排除掉
            if (map.get("pull_request") != null) {
                continue;
            }
            result.add(toSummary(map));
        }
        return result;
    }

    /** 列出某条 Issue 的评论（按创建时间正序，和 GitHub 默认顺序一致）。 */
    public List<Map<String, Object>> listComments(int number, int limit) throws IOException {
        String path = "/repos/" + repoPath() + "/issues/" + number
                + "/comments?per_page=" + Math.max(1, Math.min(100, limit));
        List<Object> array = array(request("GET", path, null));
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>(array.size());
        for (Object element : array) {
            if (element instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> map = (Map<String, Object>) element;
                result.add(map);
            }
        }
        return result;
    }

    /** 创建 Issue。 */
    public Map<String, Object> createIssue(String title, String body, List<String> labels) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("body", body);
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }
        return object(request("POST", "/repos/" + repoPath() + "/issues", MiniJson.write(payload)));
    }

    /** 给 Issue 追加一条评论。 */
    public void addComment(int number, String body) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("body", body);
        request("POST", "/repos/" + repoPath() + "/issues/" + number + "/comments", MiniJson.write(payload));
    }

    /** 关闭 Issue。 */
    public void closeIssue(int number) throws IOException {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("state", "closed");
        request("PATCH", "/repos/" + repoPath() + "/issues/" + number, MiniJson.write(payload));
    }

    /** 仓库网页地址 + 指定 Issue 编号。 */
    public String issueUrl(int number) {
        return plugin.getPluginConfig().getRepoUrl() + "/issues/" + number;
    }

    // ------------------------------------------------------------------
    // 请求（含重试与额度记录）
    // ------------------------------------------------------------------

    private Http.Response request(String method, String path, String jsonBody) throws IOException {
        PluginConfig config = plugin.getPluginConfig();
        if (!isAvailable()) {
            throw new IOException("GitHub 渠道未启用或未配置 PAT / 仓库");
        }
        String url = config.getApiBase() + path;

        int attempts = Math.max(1, config.getRetryAttempts() + 1);
        IOException lastError = null;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            Http.Response response;
            try {
                response = send(method, url, jsonBody, config);
            } catch (IOException e) {
                lastError = e;
                if (attempt < attempts) {
                    sleep(attempt);
                    continue;
                }
                throw new IOException(Http.describe(e), e);
            }

            trackRate(response);

            if (response.isSuccess()) {
                return response;
            }
            if (isRetryable(response) && attempt < attempts) {
                plugin.getLogger().fine("GitHub " + method + " " + path + " 返回 HTTP "
                        + response.getCode() + "，第 " + attempt + " 次重试");
                sleep(attempt);
                continue;
            }
            throw new IOException(describeError(response));
        }

        throw lastError == null ? new IOException("请求失败：" + url) : lastError;
    }

    private Http.Response send(String method, String url, String jsonBody, PluginConfig config)
            throws IOException {
        Map<String, String> headers = headers(config);
        if ("PATCH".equalsIgnoreCase(method)) {
            return Http.patchJson(url, headers, jsonBody, config.getTimeoutMillis(), config.resolveProxy());
        }
        return Http.request(method, url, headers, jsonBody, config.getTimeoutMillis(), config.resolveProxy());
    }

    /** 5xx、429、以及额度耗尽的 403 值得重试；其余 4xx 是请求本身的问题，重试没有意义。 */
    private static boolean isRetryable(Http.Response response) {
        int code = response.getCode();
        if (code >= 500 || code == 429) {
            return true;
        }
        return code == 403 && "0".equals(response.getRateRemaining());
    }

    private void trackRate(Http.Response response) {
        String raw = response.getRateRemaining();
        if (raw == null || raw.isEmpty() || "未知".equals(raw)) {
            return;
        }
        try {
            lastRateRemaining = Integer.parseInt(raw.trim());
        } catch (NumberFormatException ignored) {
            // 保持上一次的值
        }
    }

    private static void sleep(int attempt) {
        long millis = Math.min(RETRY_MAX_MILLIS, RETRY_BASE_MILLIS * attempt * attempt);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private Map<String, String> headers(PluginConfig config) {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + config.getToken().trim());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "AQIssue/" + plugin.getPluginMeta().getVersion());
        return headers;
    }

    private String repoPath() {
        PluginConfig config = plugin.getPluginConfig();
        return config.getOwner() + "/" + config.getRepo();
    }

    // ------------------------------------------------------------------
    // 解析与错误提示
    // ------------------------------------------------------------------

    private static IssueSummary toSummary(Map<String, Object> json) {
        int number = MiniJson.integer(json, "number", 0);
        String url = MiniJson.string(json, "html_url");
        return new IssueSummary(number, MiniJson.string(json, "title"), url,
                MiniJson.string(json, "state"), MiniJson.integer(json, "comments", 0));
    }

    private static Map<String, Object> object(Http.Response response) throws IOException {
        try {
            return MiniJson.parseObject(response.getBody());
        } catch (RuntimeException e) {
            throw new IOException("GitHub 返回了无法解析的响应");
        }
    }

    @SuppressWarnings("unchecked")
    private static List<Object> array(Http.Response response) throws IOException {
        Object parsed;
        try {
            parsed = MiniJson.parse(response.getBody());
        } catch (RuntimeException e) {
            throw new IOException("GitHub 返回了无法解析的响应");
        }
        if (!(parsed instanceof List)) {
            throw new IOException("GitHub 返回的不是列表");
        }
        return (List<Object>) parsed;
    }

    /** 把 GitHub 的错误码翻译成能照着做的提示。 */
    public static String describeError(Http.Response response) {
        String detail = null;
        try {
            detail = MiniJson.string(MiniJson.parseObject(response.getBody()), "message");
        } catch (RuntimeException ignored) {
            // 拿不到就只用状态码
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

    /** 不可变空列表，方便调用方少写判空。 */
    public static List<String> noLabels() {
        return Collections.emptyList();
    }
}
