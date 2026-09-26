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

    // 提交限制
    private int cooldownSeconds;
    private int minTitleLength;
    private int maxTitleLength;
    private int maxBodyLength;

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

        cooldownSeconds = Math.max(0, config.getInt("submit.cooldown-seconds", 300));
        minTitleLength = Math.max(1, config.getInt("submit.min-title-length", 4));
        maxTitleLength = Math.max(minTitleLength, config.getInt("submit.max-title-length", 60));
        maxBodyLength = Math.max(16, config.getInt("submit.max-body-length", 800));

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

    public int getMinTitleLength() {
        return minTitleLength;
    }

    public int getMaxTitleLength() {
        return maxTitleLength;
    }

    public int getMaxBodyLength() {
        return maxBodyLength;
    }

    public List<Category> getCategories() {
        return categories;
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
