package cn.aqcraft.iusse.net;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.Proxy;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 各通道共用的极简 HTTP 客户端。
 * <p>
 * 只用 JDK 自带的 {@link HttpURLConnection}，不引入任何第三方 HTTP 库。
 */
public final class Http {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    private Http() {
    }

    /** HTTP 响应。 */
    public static final class Response {

        private final int code;
        private final String body;
        private final String rateRemaining;
        private final Map<String, String> headers;

        Response(int code, String body, String rateRemaining, Map<String, String> headers) {
            this.code = code;
            this.body = body;
            this.rateRemaining = rateRemaining;
            this.headers = headers;
        }

        public int getCode() {
            return code;
        }

        public String getBody() {
            return body;
        }

        /** GitHub 的 X-RateLimit-Remaining，没有则为 "未知"。 */
        public String getRateRemaining() {
            return rateRemaining;
        }

        public String getHeader(String name) {
            return headers.get(name.toLowerCase());
        }

        public boolean isSuccess() {
            return code >= 200 && code < 300;
        }
    }

    /** 发起 GET 请求。 */
    public static Response get(String url, Map<String, String> headers, int timeoutMillis, Proxy proxy)
            throws IOException {
        return request("GET", url, headers, null, timeoutMillis, proxy);
    }

    /** 发起 POST 请求，body 为 JSON 字符串。 */
    public static Response postJson(String url, Map<String, String> headers, String json,
                                    int timeoutMillis, Proxy proxy) throws IOException {
        return request("POST", url, headers, json, timeoutMillis, proxy);
    }

    /**
     * 发起 PATCH 请求（用于关闭 Issue 这类「局部修改」）。
     * <p>
     * {@link HttpURLConnection#setRequestMethod(String)} 只认 GET/POST/HEAD/OPTIONS/PUT/DELETE/TRACE，
     * 遇到 PATCH 会直接抛 {@code ProtocolException: Invalid HTTP method: PATCH}，
     * 所以这里改用 JDK 11 起自带的 {@link java.net.http.HttpClient}（同样是标准库，不引入依赖）。
     */
    public static Response patchJson(String url, Map<String, String> headers, String json,
                                     int timeoutMillis, Proxy proxy) throws IOException {
        java.net.http.HttpClient.Builder builder = java.net.http.HttpClient.newBuilder()
                .connectTimeout(java.time.Duration.ofMillis(Math.max(1000, timeoutMillis)))
                .followRedirects(java.net.http.HttpClient.Redirect.NORMAL);
        if (proxy != null && proxy.address() instanceof java.net.InetSocketAddress) {
            java.net.InetSocketAddress address = (java.net.InetSocketAddress) proxy.address();
            builder.proxy(java.net.ProxySelector.of(address));
        }
        java.net.http.HttpClient client = builder.build();

        java.net.http.HttpRequest.Builder request = java.net.http.HttpRequest.newBuilder(java.net.URI.create(url))
                .timeout(java.time.Duration.ofMillis(Math.max(1000, timeoutMillis)))
                .method("PATCH", java.net.http.HttpRequest.BodyPublishers.ofString(json, UTF_8));

        if (headers != null) {
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    request.header(entry.getKey(), entry.getValue());
                }
            }
        }

        java.net.http.HttpResponse<String> response;
        try {
            response = client.send(request.build(), java.net.http.HttpResponse.BodyHandlers.ofString(UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("请求被中断", e);
        }

        Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
        for (Map.Entry<String, java.util.List<String>> entry : response.headers().map().entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                responseHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
            }
        }
        String remaining = responseHeaders.get("x-ratelimit-remaining");
        return new Response(response.statusCode(), response.body(),
                remaining == null ? "未知" : remaining, responseHeaders);
    }

    /**
     * 发起请求。
     *
     * @param jsonBody 非空时以 UTF-8 作为请求体发出，并自动补上 JSON 的 Content-Type
     */
    public static Response request(String method, String url, Map<String, String> headers, String jsonBody,
                                   int timeoutMillis, Proxy proxy) throws IOException {
        URL target = new URL(url);
        HttpURLConnection connection = (HttpURLConnection)
                (proxy == null ? target.openConnection() : target.openConnection(proxy));
        try {
            connection.setRequestMethod(method);
            connection.setConnectTimeout(timeoutMillis);
            connection.setReadTimeout(timeoutMillis);
            connection.setInstanceFollowRedirects(true);
            connection.setRequestProperty("Accept", "application/json");

            if (headers != null) {
                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    if (entry.getKey() != null && entry.getValue() != null) {
                        connection.setRequestProperty(entry.getKey(), entry.getValue());
                    }
                }
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

            int code = connection.getResponseCode();
            InputStream stream = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String body = readAll(stream);

            Map<String, String> responseHeaders = new LinkedHashMap<String, String>();
            for (Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null && !entry.getValue().isEmpty()) {
                    responseHeaders.put(entry.getKey().toLowerCase(), entry.getValue().get(0));
                }
            }
            String remaining = responseHeaders.get("x-ratelimit-remaining");
            return new Response(code, body, remaining == null ? "未知" : remaining, responseHeaders);
        } finally {
            connection.disconnect();
        }
    }

    /** 把输入流按 UTF-8 读完，流为 null 时返回空串。 */
    public static String readAll(InputStream stream) throws IOException {
        if (stream == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder();
        BufferedReader reader = new BufferedReader(new InputStreamReader(stream, UTF_8));
        try {
            char[] buffer = new char[2048];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                builder.append(buffer, 0, read);
            }
        } finally {
            close(reader);
        }
        return builder.toString();
    }

    /** 静默关闭。 */
    public static void close(java.io.Closeable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (IOException ignored) {
                // 忽略关闭异常
            }
        }
    }

    /** 把异常翻译成可操作的提示。 */
    public static String describe(IOException e) {
        if (e instanceof java.net.UnknownHostException) {
            return "无法解析目标主机，请检查服务器 DNS 或网络";
        }
        if (e instanceof java.net.SocketTimeoutException) {
            return "连接超时，可在 config.yml 中启用代理后重试";
        }
        if (e instanceof javax.net.ssl.SSLException) {
            return "TLS 握手失败（常见于国内网络直连境外服务），请在 config.yml 中启用代理";
        }
        String message = e.getMessage();
        return "网络异常：" + (message == null ? e.getClass().getSimpleName() : message);
    }
}
