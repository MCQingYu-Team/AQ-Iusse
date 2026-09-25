package cn.aqcraft.iusse.config;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
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
        token = config.getString("github.token", "");
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

    /** 仓库网页地址。 */
    public String getRepoUrl() {
        return "https://github.com/" + owner + "/" + repo;
    }

    /** owner / repo 是否已填写。 */
    public boolean isRepoConfigured() {
        return owner != null && !owner.trim().isEmpty() && repo != null && !repo.trim().isEmpty();
    }
}
