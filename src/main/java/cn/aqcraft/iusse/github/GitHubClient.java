package cn.aqcraft.iusse.github;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.UnknownHostException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;

import javax.net.ssl.SSLException;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.PluginConfig;

/**
 * GitHub REST API 客户端。
 * <p>
 * 仅使用 JDK 自带的 {@link HttpURLConnection}（Java 8 兼容），不引入任何第三方 HTTP 库，
 * 这样插件可以在任何服务端上原样运行。
 */
public class GitHubClient {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private final AqIssuePlugin plugin;

    public GitHubClient(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    private PluginConfig config() {
        return plugin.getPluginConfig();
    }

    // ------------------------------------------------------------------
    // 对外 API
    // ------------------------------------------------------------------

    /**
     * 在配置的目标仓库中创建一个 Issue。
     *
     * @param title  标题（不含前缀）
     * @param body   正文（Markdown）
     * @param labels 附加标签
     */
    public GitHubResult createIssue(String title, String body, List<String> labels) {
        PluginConfig config = config();
        if (!config.isRepoConfigured()) {
            return GitHubResult.fail(0, "未配置目标仓库（github.owner / github.repo）");
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("title", title);
        payload.put("body", body);
        if (labels != null && !labels.isEmpty()) {
            payload.put("labels", labels);
        }
        String requestBody = MiniJson.write(payload);

        Response response;
        try {
            response = request("POST",
                    "/repos/" + config.getOwner() + "/" + config.getRepo() + "/issues",
                    requestBody);
        } catch (IOException e) {
            return GitHubResult.fail(0, describeNetworkError(e));
        }

        if (response.code >= 200 && response.code < 300) {
            try {
                Map<String, Object> json = MiniJson.parseObject(response.body);
                int number = MiniJson.integer(json, "number", 0);
                String url = MiniJson.string(json, "html_url");
                return GitHubResult.ok(response.code, number, url);
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "解析 GitHub 响应失败：" + response.body, e);
                return GitHubResult.fail(response.code, "已创建但无法解析响应，请到仓库确认");
            }
        }
        return GitHubResult.fail(response.code, describeApiError(response));
    }

    /**
     * 检查仓库连通性、Token 有效性与剩余限额。
     */
    public GitHubStatus checkStatus() {
        PluginConfig config = config();
        if (!config.isRepoConfigured()) {
            return GitHubStatus.failure("未配置目标仓库（github.owner / github.repo）");
        }

        Response response;
        try {
            response = request("GET", "/repos/" + config.getOwner() + "/" + config.getRepo(), null);
        } catch (IOException e) {
            return GitHubStatus.failure(describeNetworkError(e));
        }

        if (response.code >= 200 && response.code < 300) {
            String fullName = config.getOwner() + "/" + config.getRepo();
            String privateFlag = "false";
            try {
                Map<String, Object> json = MiniJson.parseObject(response.body);
                fullName = MiniJson.string(json, "full_name");
                privateFlag = String.valueOf(json.get("private"));
            } catch (RuntimeException ignored) {
                // 解析失败不影响「连通」这个结论
            }
            return GitHubStatus.success(
                    fullName,
                    config.hasToken() ? "已配置 Token" + ("true".equals(privateFlag) ? "（私有仓库）" : "") : "匿名访问",
                    response.rateRemaining);
        }

        return GitHubStatus.failure(describeApiError(response));
    }

    // ------------------------------------------------------------------
    // HTTP
    // ------------------------------------------------------------------

    private static final class Response {
        int code;
        String body = "";
        String rateRemaining = "未知";
    }

    private Response request(String method, String path, String jsonBody) throws IOException {
        PluginConfig config = config();
        URL url = new URL(config.getApiBase() + path);

        HttpURLConnection connection;
        if (config.isProxyEnabled()) {
            Proxy proxy = new Proxy(Proxy.Type.HTTP,
                    new InetSocketAddress(config.getProxyHost(), config.getProxyPort()));
            connection = (HttpURLConnection) url.openConnection(proxy);
        } else {
            connection = (HttpURLConnection) url.openConnection();
        }

        Response response = new Response();
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(config.getTimeoutMillis());
            connection.setReadTimeout(config.getTimeoutMillis());
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/vnd.github+json");
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            connection.setRequestProperty("User-Agent",
                    "AQIssue/" + plugin.getPluginMeta().getVersion() + " (+" + config.getRepoUrl() + ")");
            if (config.hasToken()) {
                connection.setRequestProperty("Authorization", "Bearer " + config.getToken().trim());
            }

            if (jsonBody != null) {
                byte[] bytes = jsonBody.getBytes(UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(bytes.length);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                OutputStream out = connection.getOutputStream();
                try {
                    out.write(bytes);
                    out.flush();
                } finally {
                    close(out);
                }
            }

            response.code = connection.getResponseCode();
            String remaining = connection.getHeaderField("X-RateLimit-Remaining");
            if (remaining != null) {
                response.rateRemaining = remaining;
            }
            InputStream stream = response.code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            response.body = readAll(stream);
        } finally {
            connection.disconnect();
        }
        return response;
    }

    private static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8));
        try {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, read);
            }
        } finally {
            close(reader);
        }
        return sb.toString();
    }

    private static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    // ------------------------------------------------------------------
    // 错误信息
    // ------------------------------------------------------------------

    /** 把 GitHub 返回的错误 JSON 翻译成人类可读的中文提示。 */
    private String describeApiError(Response response) {
        String detail = null;
        try {
            Map<String, Object> json = MiniJson.parseObject(response.body);
            detail = MiniJson.string(json, "message");
            Object errors = json.get("errors");
            if (errors instanceof List && !((List<?>) errors).isEmpty()) {
                Object first = ((List<?>) errors).get(0);
                if (first instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> errorMap = (Map<String, Object>) first;
                    String field = MiniJson.string(errorMap, "field");
                    String message = MiniJson.string(errorMap, "message");
                    if (message != null) {
                        detail = (field == null ? "" : field + ": ") + message;
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // 保留原始响应作为兜底
        }

        String hint;
        switch (response.code) {
            case 401:
                hint = "Token 无效或已过期，请更新 config.yml 中的 github.token";
                break;
            case 403:
                hint = "0".equals(response.rateRemaining)
                        ? "API 访问次数已用尽，请稍后再试或配置 Token"
                        : "权限不足，请确认 Token 拥有 Issues: Read and write 权限";
                break;
            case 404:
                hint = "仓库不存在，或 Token 无权访问该仓库";
                break;
            case 410:
                hint = "该仓库已关闭 Issues 功能";
                break;
            case 422:
                hint = "内容校验失败（可能是标签不存在或标题重复）";
                break;
            default:
                hint = response.code >= 500 ? "GitHub 服务端异常，请稍后再试" : "请求被 GitHub 拒绝";
                break;
        }

        String message = hint + "（HTTP " + response.code + "）";
        if (detail != null && !detail.isEmpty()) {
            message = message + "：" + detail;
        }
        plugin.getLogger().warning("GitHub API 调用失败 " + response.code + " -> " + response.body);
        return message;
    }

    /** 把网络层异常翻译成可操作的提示。 */
    private String describeNetworkError(IOException e) {
        plugin.getLogger().log(Level.WARNING, "访问 GitHub API 时发生网络异常", e);
        if (e instanceof UnknownHostException) {
            return "无法解析 " + config().getApiBase() + "，请检查服务器 DNS 或网络";
        }
        if (e instanceof SocketTimeoutException) {
            return "连接 GitHub 超时，可在 config.yml 中启用 github.proxy 后重试";
        }
        if (e instanceof SSLException) {
            return "TLS 握手失败（常见于国内网络），请在 config.yml 中启用 github.proxy";
        }
        String message = e.getMessage();
        return "网络异常：" + (message == null ? e.getClass().getSimpleName() : message);
    }
}
