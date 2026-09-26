package cn.aqcraft.iusse.github;

import java.io.IOException;
import java.net.Proxy;
import java.nio.charset.Charset;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.Signature;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.aqcraft.iusse.net.Http;

/**
 * GitHub App 认证。
 * <p>
 * 用「App ID + 安装 ID + 私钥」自签 JWT，再换取 installation access token。
 * 相比 PAT：
 * <ul>
 *   <li>身份是机器人（Issue 显示为 {@code xxx[bot]}）</li>
 *   <li>token 一小时自动轮换，不会像 PAT 那样过期失效</li>
 *   <li>权限只来自 App 安装时授予的范围</li>
 * </ul>
 * JWT 用 JDK 自带的 {@code SHA256withRSA} 签名，PKCS#1 私钥转 PKCS#8 见 {@link Pem}。
 */
public class GitHubAppAuth {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** 提前多久刷新 token（毫秒）。 */
    private static final long REFRESH_MARGIN_MILLIS = 60_000L;

    private final String apiBase;
    private final String appId;
    private final long installationId;
    private final PrivateKey privateKey;
    private final int timeoutMillis;
    private final Proxy proxy;
    private final String userAgent;

    private volatile String token;
    private volatile long expiresAtMillis;

    public GitHubAppAuth(String apiBase, String appId, long installationId, String privateKeyPem,
                         int timeoutMillis, Proxy proxy, String userAgent) throws GeneralSecurityException {
        this.apiBase = apiBase;
        this.appId = appId;
        this.installationId = installationId;
        this.privateKey = Pem.readRsaPrivateKey(privateKeyPem);
        this.timeoutMillis = timeoutMillis;
        this.proxy = proxy;
        this.userAgent = userAgent;
    }

    /** 取当前可用的 installation token，必要时自动刷新。 */
    public synchronized String getToken() throws IOException {
        if (token != null && System.currentTimeMillis() < expiresAtMillis - REFRESH_MARGIN_MILLIS) {
            return token;
        }
        refresh();
        return token;
    }

    /** 供 /iusse status 展示 token 状态。 */
    public String describe() {
        if (token == null) {
            return "尚未获取";
        }
        long seconds = Math.max(0L, (expiresAtMillis - System.currentTimeMillis()) / 1000L);
        return "机器人 token 有效（剩余 " + seconds / 60L + " 分钟）";
    }

    private void refresh() throws IOException {
        String jwt = createJwt();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + jwt);
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", userAgent);

        String url = apiBase + "/app/installations/" + installationId + "/access_tokens";
        Http.Response response = Http.postJson(url, headers, "{}", timeoutMillis, proxy);
        if (!response.isSuccess()) {
            throw new IOException(describeError(response));
        }

        String value;
        String expiresAt;
        try {
            Map<String, Object> json = MiniJson.parseObject(response.getBody());
            value = MiniJson.string(json, "token");
            expiresAt = MiniJson.string(json, "expires_at");
        } catch (RuntimeException e) {
            throw new IOException("解析 GitHub 响应失败：" + response.getBody(), e);
        }
        if (value == null || value.isEmpty()) {
            throw new IOException("GitHub 未返回 installation token");
        }

        this.token = value;
        this.expiresAtMillis = parseExpiresAt(expiresAt);
    }

    private long parseExpiresAt(String value) {
        if (value != null) {
            try {
                return Instant.parse(value).toEpochMilli();
            } catch (RuntimeException ignored) {
                // 落到下面的兜底值
            }
        }
        // GitHub 签发的一律是 1 小时有效期
        return System.currentTimeMillis() + 3600_000L;
    }

    /** 生成 RS256 的 JWT。 */
    private String createJwt() throws IOException {
        long now = System.currentTimeMillis() / 1000L;
        String header = base64Url("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".getBytes(UTF_8));
        String payload = base64Url(("{\"iat\":" + (now - 60L)
                + ",\"exp\":" + (now + 480L)
                + ",\"iss\":\"" + appId + "\"}").getBytes(UTF_8));
        String signingInput = header + "." + payload;
        try {
            Signature signature = Signature.getInstance("SHA256withRSA");
            signature.initSign(privateKey);
            signature.update(signingInput.getBytes(UTF_8));
            return signingInput + "." + base64Url(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IOException("JWT 签名失败：" + e.getMessage(), e);
        }
    }

    private static String base64Url(byte[] data) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(data);
    }

    private String describeError(Http.Response response) {
        String detail = null;
        try {
            detail = MiniJson.string(MiniJson.parseObject(response.getBody()), "message");
        } catch (RuntimeException ignored) {
            // 保留原始响应作兜底
        }
        String hint;
        switch (response.getCode()) {
            case 401:
                hint = "App ID 或私钥不正确（JWT 被拒绝）";
                break;
            case 404:
                hint = "Installation ID 不存在，或 App 未安装到该仓库";
                break;
            case 403:
                hint = "App 没有被授予 Issues 权限，或触发了限流";
                break;
            default:
                hint = "GitHub 拒绝了 token 申请";
                break;
        }
        String message = hint + "（HTTP " + response.getCode() + "）";
        return detail == null || detail.isEmpty() ? message : message + "：" + detail;
    }
}
