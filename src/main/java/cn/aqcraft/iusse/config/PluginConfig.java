package cn.aqcraft.iusse.config;

import java.net.InetSocketAddress;
import java.net.Proxy;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.configuration.file.FileConfiguration;

import cn.aqcraft.iusse.AqIssuePlugin;

/**
 * 插件功能配置的只读视图。
 * <p>
 * 所有字段在 {@link #load()} 时一次性解析，避免在事件与对话框回调里反复访问 YAML。
 * 面向玩家的文本不在这里，统一由 {@link LangConfig} 提供。
 */
public class PluginConfig {

    private final AqIssuePlugin plugin;

    // GitHub
    private String apiBase;
    private String owner;
    private String repo;
    private String token;
    private List<String> labels;
    private String titlePrefix;
    private boolean includePlayerInfo;
    private boolean includeServerInfo;
    private int timeoutMillis;
    private boolean proxyEnabled;
    private String proxyHost;
    private int proxyPort;
    /** 网络抖动 / GitHub 5xx 时的重试次数。 */
    private int retryAttempts;

    // 提交限制
    private int cooldownSeconds;
    private String bypassPermission;
    private int minTitleLength;
    private int maxTitleLength;
    private int maxBodyLength;
    private int maxReplyLength;
    /** 提交前检查是否已有相似反馈。 */
    private boolean duplicateCheck;
    /** 判定为疑似重复的相似度阈值。 */
    private double duplicateThreshold;
    /** 自动给正文里的 IP / QQ 等打码。 */
    private boolean maskSensitive;
    /** 全部渠道失败时是否存盘等待重发。 */
    private boolean retryQueueEnabled;
    private int retryIntervalMinutes;
    private int retryMaxAttempts;
    private int retryKeepHours;

    // 渠道：GitHub（PAT）
    private boolean githubEnabled;

    // 渠道：Discord Webhook
    private boolean discordEnabled;
    private String discordWebhookUrl;
    private String discordUsername;
    private int discordEmbedColor;

    // 渠道：OneBot（server = 反向 WS 服务端；client = 主动连 NapCat 的正向 WS）
    private boolean onebotEnabled;
    private String onebotMode;
    private String onebotUrl;
    private int onebotPort;
    private String onebotBind;
    private String onebotPath;
    private String onebotAccessToken;
    private List<Long> onebotGroupIds;
    private List<Long> onebotPrivateIds;
    private long onebotTimeoutMillis;

    // 反馈跟踪（Issue 状态回传 + SLA 提醒）
    private boolean trackingEnabled;
    private int trackingIntervalMinutes;
    private int trackingMaxPerRun;
    private int trackingKeepDays;
    private boolean trackingQueueOffline;
    private boolean notifyQqOffline;
    private boolean notifyOnClose;
    private boolean notifyOnComment;
    private boolean announceOnClose;
    private boolean slaEnabled;
    private int slaHours;
    private int slaRepeatHours;
    private boolean slaBroadcast;
    private boolean slaNotifyGroups;
    private boolean slaNotifyPrivate;
    /** 剩余额度低于该值时自动降低轮询频率。 */
    private int rateLimitThreshold;

    // 分类
    private List<Category> categories;

    public PluginConfig(AqIssuePlugin plugin) {
        this.plugin = plugin;
        load();
    }

    /** 从 config.yml 重新读取全部配置。 */
    public void load() {
        FileConfiguration config = plugin.getConfig();
        config.options().copyDefaults(true);

        apiBase = trimTrailingSlash(config.getString("github.api-base", "https://api.github.com"));
        owner = config.getString("github.owner", "");
        repo = config.getString("github.repo", "");
        // 凭据来源稍后统一决定（新版在 channels.github，旧版在顶层 github.token）
        token = "";
        labels = orEmpty(config.getStringList("github.labels"));
        titlePrefix = config.getString("github.title-prefix", "");
        includePlayerInfo = config.getBoolean("github.include-player-info", true);
        includeServerInfo = config.getBoolean("github.include-server-info", true);
        timeoutMillis = Math.max(1000, config.getInt("github.timeout-millis", 10000));
        proxyEnabled = config.getBoolean("github.proxy.enabled", false);
        proxyHost = config.getString("github.proxy.host", "127.0.0.1");
        proxyPort = config.getInt("github.proxy.port", 7890);
        retryAttempts = Math.max(0, config.getInt("github.retry", 2));

        cooldownSeconds = Math.max(0, config.getInt("submit.cooldown-seconds", 300));
        bypassPermission = config.getString("submit.bypass-permission", "aqissue.admin").trim();
        minTitleLength = Math.max(1, config.getInt("submit.min-title-length", 4));
        maxTitleLength = Math.max(minTitleLength, config.getInt("submit.max-title-length", 60));
        maxBodyLength = Math.max(16, config.getInt("submit.max-body-length", 800));
        maxReplyLength = Math.max(8, config.getInt("submit.max-reply-length", 300));
        duplicateCheck = config.getBoolean("submit.duplicate-check", true);
        duplicateThreshold = clamp(config.getDouble("submit.duplicate-threshold", 0.6), 0.1D, 1.0D);
        maskSensitive = config.getBoolean("submit.mask-sensitive", true);
        retryQueueEnabled = config.getBoolean("submit.retry-queue", true);
        retryIntervalMinutes = Math.max(1, config.getInt("submit.retry-interval-minutes", 10));
        retryMaxAttempts = Math.max(1, config.getInt("submit.retry-max-attempts", 5));
        retryKeepHours = Math.max(1, config.getInt("submit.retry-keep-hours", 72));

        githubEnabled = config.getBoolean("channels.github.enabled", true);
        // 兼容更早的写法：token 写在 channels.github.token，旧配置写在顶层 github.token
        token = config.getString("channels.github.token", "").trim();
        if (token.isEmpty()) {
            token = config.getString("github.token", "").trim();
        }

        discordEnabled = config.getBoolean("channels.discord.enabled", false);
        discordWebhookUrl = config.getString("channels.discord.webhook-url", "").trim();
        discordUsername = config.getString("channels.discord.username", "服务器反馈");
        discordEmbedColor = config.getInt("channels.discord.embed-color", 0x5865F2);

        onebotEnabled = config.getBoolean("channels.onebot.enabled", false);
        onebotMode = config.getString("channels.onebot.mode", "server").trim().toLowerCase(Locale.ROOT);
        onebotUrl = config.getString("channels.onebot.url", "").trim();
        onebotPort = config.getInt("channels.onebot.port", 6700);
        onebotBind = config.getString("channels.onebot.bind", "127.0.0.1");
        onebotPath = config.getString("channels.onebot.path", "/onebot");
        onebotAccessToken = config.getString("channels.onebot.access-token", "").trim();
        onebotGroupIds = config.getLongList("channels.onebot.group-ids");
        if (onebotGroupIds == null) {
            onebotGroupIds = Collections.emptyList();
        }
        onebotPrivateIds = config.getLongList("channels.onebot.private-ids");
        if (onebotPrivateIds == null) {
            onebotPrivateIds = Collections.emptyList();
        }
        onebotTimeoutMillis = Math.max(1000L, config.getLong("channels.onebot.timeout-millis", 8000L));

        trackingEnabled = config.getBoolean("tracking.enabled", true);
        trackingIntervalMinutes = Math.max(1, config.getInt("tracking.interval-minutes", 10));
        trackingMaxPerRun = Math.max(1, config.getInt("tracking.max-per-run", 10));
        trackingKeepDays = Math.max(1, config.getInt("tracking.keep-days", 30));
        trackingQueueOffline = config.getBoolean("tracking.queue-offline", true);
        notifyQqOffline = config.getBoolean("tracking.notify-qq-offline", true);
        notifyOnClose = config.getBoolean("tracking.notify-on-close", true);
        notifyOnComment = config.getBoolean("tracking.notify-on-comment", true);
        announceOnClose = config.getBoolean("tracking.announce-on-close", true);
        slaEnabled = config.getBoolean("tracking.sla.enabled", true);
        slaHours = Math.max(1, config.getInt("tracking.sla.hours", 48));
        slaRepeatHours = Math.max(1, config.getInt("tracking.sla.repeat-hours", 24));
        slaBroadcast = config.getBoolean("tracking.sla.broadcast", true);
        slaNotifyGroups = config.getBoolean("tracking.sla.notify-groups", false);
        slaNotifyPrivate = config.getBoolean("tracking.sla.notify-private", true);
        rateLimitThreshold = Math.max(0, config.getInt("tracking.rate-limit-threshold", 100));

        categories = readCategories(config);
    }

    private List<Category> readCategories(FileConfiguration config) {
        List<Category> result = new ArrayList<Category>();
        for (Map<?, ?> map : config.getMapList("categories")) {
            Category category = Category.fromMap(map);
            if (category != null) {
                result.add(category);
            }
        }
        if (result.isEmpty()) {
            plugin.getLogger().warning("config.yml 中未配置任何反馈分类（categories），将使用内置默认分类。");
            result.add(new Category("other", "其它问题", "不属于其它分类的内容",
                    Collections.singletonList("question")));
        }
        return Collections.unmodifiableList(result);
    }

    private static List<String> orEmpty(List<String> list) {
        return list == null ? Collections.<String>emptyList() : list;
    }

    private static String trimTrailingSlash(String raw) {
        if (raw == null) {
            return "";
        }
        String value = raw.trim();
        while (value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }

    private static double clamp(double value, double min, double max) {
        if (value < min) {
            return min;
        }
        return value > max ? max : value;
    }

    /** 按 id 查找分类，找不到返回 {@code null}。 */
    public Category findCategory(String id) {
        if (id == null) {
            return null;
        }
        String normalized = id.trim().toLowerCase();
        for (Category category : categories) {
            if (category.getId().equals(normalized)) {
                return category;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // getter
    // ------------------------------------------------------------------

    public String getApiBase() {
        return apiBase;
    }

    public String getOwner() {
        return owner;
    }

    public String getRepo() {
        return repo;
    }

    public String getToken() {
        return token;
    }

    public boolean hasToken() {
        return token != null && !token.trim().isEmpty();
    }

    public List<String> getLabels() {
        return labels;
    }

    public String getTitlePrefix() {
        return titlePrefix == null ? "" : titlePrefix;
    }

    public boolean isIncludePlayerInfo() {
        return includePlayerInfo;
    }

    public boolean isIncludeServerInfo() {
        return includeServerInfo;
    }

    public int getTimeoutMillis() {
        return timeoutMillis;
    }

    public boolean isProxyEnabled() {
        return proxyEnabled;
    }

    public String getProxyHost() {
        return proxyHost;
    }

    public int getProxyPort() {
        return proxyPort;
    }

    public int getCooldownSeconds() {
        return cooldownSeconds;
    }

    /**
     * 不受提交限制（冷却与长度校验）的权限节点。
     *
     * @return 权限字符串；留空表示所有人一视同仁
     */
    public String getBypassPermission() {
        return bypassPermission == null ? "" : bypassPermission;
    }

    public int getMinTitleLength() {
        return minTitleLength;
    }

    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    /** 玩家用 {@code /iusse reply} 回复反馈时的内容长度上限。 */
    public int getMaxReplyLength() {
        return maxReplyLength;
    }

    /** 提交前是否检查已有相似反馈。 */
    public boolean isDuplicateCheck() {
        return duplicateCheck;
    }

    /** 判定为疑似重复的相似度阈值（0.1 ~ 1.0）。 */
    public double getDuplicateThreshold() {
        return duplicateThreshold;
    }

    /** 是否自动给正文里的 IP / QQ 等隐私信息打码。 */
    public boolean isMaskSensitive() {
        return maskSensitive;
    }

    /** 全部渠道投递失败时，是否存盘等待重发。 */
    public boolean isRetryQueueEnabled() {
        return retryQueueEnabled;
    }

    public int getRetryIntervalMinutes() {
        return retryIntervalMinutes;
    }

    public int getRetryMaxAttempts() {
        return retryMaxAttempts;
    }

    public int getRetryKeepHours() {
        return retryKeepHours;
    }

    /** GitHub 请求失败时的重试次数（不含首次）。 */
    public int getRetryAttempts() {
        return retryAttempts;
    }

    /** 剩余额度低于该值时自动降低轮询频率，0 表示不降频。 */
    public int getRateLimitThreshold() {
        return rateLimitThreshold;
    }

    public List<Category> getCategories() {
        return categories;
    }

    // ------------------------------------------------------------------
    // 反馈跟踪
    // ------------------------------------------------------------------

    public boolean isTrackingEnabled() {
        return trackingEnabled;
    }

    /** 轮询间隔（分钟）。 */
    public int getTrackingIntervalMinutes() {
        return trackingIntervalMinutes;
    }

    /** 每轮最多查询多少个 Issue。 */
    public int getTrackingMaxPerRun() {
        return trackingMaxPerRun;
    }

    /** 只跟踪最近多少天内提交的反馈。 */
    public int getTrackingKeepDays() {
        return trackingKeepDays;
    }

    /** 玩家离线时是否把通知排队、等他上线补发。 */
    public boolean isTrackingQueueOffline() {
        return trackingQueueOffline;
    }

    /** 玩家离线时，是否经 EasyBot 查其绑定的 QQ 并私信通知。 */
    public boolean isNotifyQqOffline() {
        return notifyQqOffline;
    }

    public boolean isNotifyOnClose() {
        return notifyOnClose;
    }

    public boolean isNotifyOnComment() {
        return notifyOnComment;
    }

    /** Issue 处理完成（被关闭）时，是否也在渠道里播报一条（QQ 群 / Discord）。 */
    public boolean isTrackingAnnounceOnClose() {
        return announceOnClose;
    }

    public boolean isSlaEnabled() {
        return slaEnabled;
    }

    /** 超过多少小时未处理就提醒管理员。 */
    public int getSlaHours() {
        return slaHours;
    }

    /** 同一 Issue 的重复提醒间隔（小时）。 */
    public int getSlaRepeatHours() {
        return slaRepeatHours;
    }

    /** 除控制台外，是否也把超时提醒发到已启用的渠道。 */
    public boolean isSlaBroadcast() {
        return slaBroadcast;
    }

    /** 超时提醒是否发到 QQ 群。 */
    public boolean isSlaNotifyGroups() {
        return slaNotifyGroups;
    }

    /** 超时提醒是否私信给 private-ids。 */
    public boolean isSlaNotifyPrivate() {
        return slaNotifyPrivate;
    }

    // ------------------------------------------------------------------
    // 渠道配置
    // ------------------------------------------------------------------

    public boolean isGitHubEnabled() {
        return githubEnabled;
    }

    public boolean isDiscordEnabled() {
        return discordEnabled;
    }

    public String getDiscordWebhookUrl() {
        return discordWebhookUrl == null ? "" : discordWebhookUrl;
    }

    public String getDiscordUsername() {
        return discordUsername == null ? "" : discordUsername;
    }

    public int getDiscordEmbedColor() {
        return discordEmbedColor;
    }

    public boolean isOneBotEnabled() {
        return onebotEnabled;
    }

    /** true 表示插件主动去连 NapCat 的 WebSocket 服务端（正向 WS）。 */
    public boolean isOneBotClientMode() {
        return "client".equals(onebotMode);
    }

    public String getOneBotMode() {
        return onebotMode == null || onebotMode.isEmpty() ? "server" : onebotMode;
    }

    /** 客户端模式下要连接的地址，形如 ws://host:port/path。 */
    public String getOneBotUrl() {
        return onebotUrl == null ? "" : onebotUrl;
    }

    public int getOneBotPort() {
        return onebotPort;
    }

    public String getOneBotBind() {
        return onebotBind == null || onebotBind.isEmpty() ? "127.0.0.1" : onebotBind;
    }

    public String getOneBotPath() {
        return onebotPath == null || onebotPath.isEmpty() ? "/onebot" : onebotPath;
    }

    public String getOneBotAccessToken() {
        return onebotAccessToken == null ? "" : onebotAccessToken;
    }

    public List<Long> getOneBotGroupIds() {
        return onebotGroupIds == null ? Collections.<Long>emptyList() : onebotGroupIds;
    }

    /** 私聊接收者的 QQ 号（一般是管理员）。 */
    public List<Long> getOneBotPrivateIds() {
        return onebotPrivateIds == null ? Collections.<Long>emptyList() : onebotPrivateIds;
    }

    public long getOneBotTimeoutMillis() {
        return onebotTimeoutMillis;
    }

    /** 按配置构造代理对象，未启用时返回 {@code null}。 */
    public Proxy resolveProxy() {
        if (!proxyEnabled) {
            return null;
        }
        return new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
    }

    /** 仓库网页地址。 */
    public String getRepoUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** owner / repo 是否已填写。 */
    public boolean isRepoConfigured() {
        return owner != null && !owner.trim().isEmpty() && repo != null && !repo.trim().isEmpty();
    }
}
