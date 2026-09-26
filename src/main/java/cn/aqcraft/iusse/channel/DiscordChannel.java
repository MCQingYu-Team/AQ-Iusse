package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.net.Proxy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.net.Http;
import cn.aqcraft.iusse.util.Text;

/**
 * Discord 通道：往频道 Webhook 发一条 Embed。
 * <p>
 * 用 Webhook 而不是 Bot，是因为不需要机器人常驻在线、也不需要在开发者后台建 Application，
 * 只需要在频道设置里复制一个 URL。
 */
public class DiscordChannel implements Channel {

    public static final String ID = "discord";

    /** Embed 各字段的长度上限（Discord 侧硬限制）。 */
    private static final int MAX_TITLE = 256;
    private static final int MAX_DESCRIPTION = 4096;
    private static final int MAX_FOOTER = 2048;

    private final PluginConfig config;
    private final Proxy proxy;
    private final String disabledReason;

    public DiscordChannel(PluginConfig config) {
        this.config = config;
        this.proxy = config.resolveProxy();

        String reason = null;
        if (!config.isDiscordEnabled()) {
            reason = "配置中未启用";
        } else if (config.getDiscordWebhookUrl().isEmpty()) {
            reason = "未填写 channels.discord.webhook-url";
        } else if (!config.getDiscordWebhookUrl().startsWith("https://")) {
            reason = "webhook-url 必须是 https:// 开头的完整地址";
        }
        this.disabledReason = reason;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "Discord";
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
        // Webhook 无需常驻连接
    }

    @Override
    public void stop() {
        // 无需释放资源
    }

    @Override
    public ChannelResult submit(Submission submission) throws IOException {
        Map<String, Object> embed = new LinkedHashMap<String, Object>();
        embed.put("title", Text.truncate(submission.getTitle(), MAX_TITLE));
        embed.put("description", Text.truncate(submission.toPlainText(), MAX_DESCRIPTION));
        embed.put("color", config.getDiscordEmbedColor());
        embed.put("timestamp", submission.getIsoTime());
        embed.put("footer", Collections.singletonMap("text",
                Text.truncate("AQIssue | " + submission.getCategoryName(), MAX_FOOTER)));
        // 有链接时把标题变成可点击的跳转
        String link = submission.getPrimaryLink();
        if (link != null) {
            embed.put("url", link);
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        String username = config.getDiscordUsername();
        if (!username.isEmpty()) {
            payload.put("username", Text.truncate(username, 80));
        }
        payload.put("embeds", Collections.singletonList(embed));

        Http.Response response = Http.postJson(config.getDiscordWebhookUrl(), null,
                MiniJson.write(payload), config.getTimeoutMillis(), proxy);

        // Discord Webhook 成功时返回 204 No Content
        if (response.getCode() == 204 || response.isSuccess()) {
            return ChannelResult.success(this, "已发送到 Discord");
        }
        throw new IOException(describeError(response));
    }

    @Override
    public String checkStatus() throws IOException {
        String url = config.getDiscordWebhookUrl();
        if (url.startsWith("https://discord.com/api/webhooks/")) {
            Http.Response response = Http.get(url, null, config.getTimeoutMillis(), proxy);
            if (response.getCode() == 401 || response.getCode() == 404) {
                throw new IOException("Webhook 无效或已被删除（HTTP " + response.getCode() + "）");
            }
            return "Webhook 可用";
        }
        return "Webhook 已配置";
    }

    /** 向频道推送一条纯文本系统通知（如 SLA 超时提醒）。 */
    @Override
    public boolean notifyAdmins(String message) {
        try {
            Map<String, Object> payload = new LinkedHashMap<String, Object>();
            String username = config.getDiscordUsername();
            if (!username.isEmpty()) {
                payload.put("username", Text.truncate(username, 80));
            }
            payload.put("content", Text.truncate(Text.plain(message), MAX_DESCRIPTION));

            Http.Response response = Http.postJson(config.getDiscordWebhookUrl(), null,
                    MiniJson.write(payload), config.getTimeoutMillis(), proxy);
            return response.getCode() == 204 || response.isSuccess();
        } catch (IOException e) {
            return false;
        }
    }

    private String describeError(Http.Response response) {
        String detail = null;
        try {
            Map<String, Object> json = MiniJson.parseObject(response.getBody());
            detail = MiniJson.string(json, "message");
        } catch (RuntimeException ignored) {
            // 保留原始响应作兜底
        }
        String hint;
        switch (response.getCode()) {
            case 401:
            case 403:
                hint = "Webhook 无效或已被删除";
                break;
            case 404:
                hint = "Webhook 不存在，请重新复制 URL";
                break;
            case 429:
                hint = "触发 Discord 限流，稍后再试";
                break;
            default:
                hint = response.getCode() >= 500 ? "Discord 服务端异常" : "请求被 Discord 拒绝";
                break;
        }
        String message = hint + "（HTTP " + response.getCode() + "）";
        return detail == null || detail.isEmpty() ? message : message + "：" + detail;
    }
}
