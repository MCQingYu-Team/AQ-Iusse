package cn.aqcraft.iusse;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import cn.aqcraft.iusse.channel.ChannelManager;
import cn.aqcraft.iusse.channel.ChannelResult;
import cn.aqcraft.iusse.channel.GitHubChannel;
import cn.aqcraft.iusse.channel.Submission;
import cn.aqcraft.iusse.command.IssueCommand;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.LangConfig;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.dialog.FeedbackDialog;
import cn.aqcraft.iusse.github.GitHubApi;
import cn.aqcraft.iusse.listener.PlayerJoinListener;
import cn.aqcraft.iusse.session.CooldownManager;
import cn.aqcraft.iusse.tracking.FeedbackQueue;
import cn.aqcraft.iusse.tracking.IssueTracker;
import cn.aqcraft.iusse.util.Sanitizer;
import cn.aqcraft.iusse.util.Similarity;
import cn.aqcraft.iusse.util.Text;

/**
 * AQIssue 主类：把游戏内的反馈同时投递到 GitHub / Discord / QQ 群。
 * <p>
 * 入口是 Paper 原生对话框，一次提交会广播到所有已启用的渠道。
 */
public class AqIssuePlugin extends JavaPlugin {

    /** 重复检测的 Issue 列表缓存时间，避免每次提交都打一次 API。 */
    private static final long DUPLICATE_CACHE_MILLIS = 300000L;

    private PluginConfig pluginConfig;
    private LangConfig lang;
    private CooldownManager cooldownManager;
    private GitHubApi gitHubApi;
    private ChannelManager channelManager;
    private FeedbackDialog feedbackDialog;
    private IssueTracker issueTracker;
    private FeedbackQueue feedbackQueue;

    private final Object duplicateLock = new Object();
    /** 最近拉取到的未关闭 Issue（重复检测用）。 */
    private volatile List<GitHubApi.IssueSummary> recentIssues;
    private volatile long recentIssuesAt;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.lang = new LangConfig(this);
        this.pluginConfig = new PluginConfig(this);
        this.cooldownManager = new CooldownManager();
        this.gitHubApi = new GitHubApi(this);
        this.channelManager = new ChannelManager(this);
        this.feedbackDialog = new FeedbackDialog(this);
        this.issueTracker = new IssueTracker(this);
        this.feedbackQueue = new FeedbackQueue(this);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);

        PluginCommand command = getCommand("iusse");
        if (command != null) {
            IssueCommand executor = new IssueCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            getLogger().info("指令注册成功：/" + command.getName()
                    + (command.getAliases().isEmpty() ? ""
                    : "（别名 /" + String.join("、/", command.getAliases()) + "）"));
        } else {
            getLogger().severe("无法注册指令 /iusse —— plugin.yml 里的 commands 段可能与其他插件冲突。");
        }

        channelManager.reload();
        issueTracker.start();
        feedbackQueue.start();

        // 每小时清理一次已过期的冷却记录
        getServer().getScheduler().runTaskTimer(this, new Runnable() {
            @Override
            public void run() {
                cooldownManager.purge(pluginConfig.getCooldownSeconds());
            }
        }, 72000L, 72000L);

        logStartupSummary();
    }

    @Override
    public void onDisable() {
        if (issueTracker != null) {
            issueTracker.stop();
        }
        if (feedbackQueue != null) {
            feedbackQueue.stop();
        }
        if (channelManager != null) {
            channelManager.stopAll();
        }
        if (cooldownManager != null) {
            cooldownManager.clear();
        }
    }

    private void logStartupSummary() {
        getLogger().info("语言文件：" + lang.getFileName() + " (" + lang.getLocale() + ")");
        if (pluginConfig.isRepoConfigured()) {
            getLogger().info("GitHub 仓库：" + pluginConfig.getRepoUrl());
        }
        if (pluginConfig.isGitHubEnabled() && !pluginConfig.hasToken()) {
            getLogger().warning("GitHub 渠道已启用，但未填写 PAT（channels.github.token），该渠道会投递失败。");
        }
        if (!pluginConfig.isGitHubEnabled() && !pluginConfig.isDiscordEnabled() && !pluginConfig.isOneBotEnabled()) {
            getLogger().warning("未启用任何投递渠道，请在 config.yml 的 channels 段中至少开启一个。");
        }
        // EasyBot 是可选的：不可用时只影响「玩家离线时用 QQ 私信通知」，其余功能不受影响，
        // 但把原因写进日志，免得排查时只能看到一句「未安装」
        getLogger().info("EasyBot 联动：" + cn.aqcraft.iusse.integration.EasyBotBridge.describeState());
    }

    /** 重新读取配置与语言文件（/iusse reload）。 */
    public void reloadAll() {
        reloadConfig();
        pluginConfig.load();
        lang.load();
        feedbackQueue.reschedule();
        // 配置变了，重复检测的缓存也要作废
        recentIssues = null;
        recentIssuesAt = 0L;
        cooldownManager.clear();
        channelManager.reload();
        issueTracker.reschedule();
        logStartupSummary();
    }

    // ------------------------------------------------------------------
    // 消息
    // ------------------------------------------------------------------

    /** 发送一条带前缀的语言文件消息，占位符成对传入：key1, value1, key2, value2... */
    public void send(Player player, String key, Object... placeholders) {
        player.sendMessage(lang.prefixed(key, placeholders));
    }

    /** 发送一条带前缀的现成文本。 */
    public void sendRaw(Player player, String message) {
        player.sendMessage(lang.prefix() + message);
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    /** 打开反馈对话框。 */
    public void openDialog(Player player) {
        if (!checkCooldown(player)) {
            return;
        }
        if (channelManager.isEmpty()) {
            send(player, "submit.no-channel");
            return;
        }
        try {
            feedbackDialog.open(player);
        } catch (Throwable throwable) {
            logError("打开反馈对话框失败", throwable);
            send(player, "dialog.unsupported");
        }
    }

    /**
     * 校验来自对话框或指令的输入，通过后开始投递。
     *
     * @return true 表示校验通过并已开始投递
     */
    public boolean submitFromInput(Player player, String categoryId, String title, String body) {
        Category category = pluginConfig.findCategory(categoryId);
        if (category == null) {
            send(player, "general.unknown-category", "id", categoryId);
            return false;
        }
        if (channelManager.isEmpty()) {
            send(player, "submit.no-channel");
            return false;
        }
        if (!checkCooldown(player)) {
            return false;
        }

        boolean bypass = isBypassing(player);

        String cleanTitle = Text.oneLine(Text.plain(title));
        // 标题与内容不能为空这条对所有人一视同仁，其余限制管理员可豁免
        if (cleanTitle.isEmpty()) {
            send(player, "submit.title-too-short", "min", pluginConfig.getMinTitleLength());
            return false;
        }
        if (!bypass) {
            if (cleanTitle.length() < pluginConfig.getMinTitleLength()) {
                send(player, "submit.title-too-short", "min", pluginConfig.getMinTitleLength());
                return false;
            }
            if (cleanTitle.length() > pluginConfig.getMaxTitleLength()) {
                send(player, "submit.title-too-long", "max", pluginConfig.getMaxTitleLength());
                return false;
            }
        }

        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.isEmpty()) {
            send(player, "submit.body-empty");
            return false;
        }
        if (!bypass && cleanBody.length() > pluginConfig.getMaxBodyLength()) {
            send(player, "submit.body-too-long", "max", pluginConfig.getMaxBodyLength());
            return false;
        }

        // 打码放在长度校验之后：限制按玩家实际输入算，投递的才是处理过的文本
        boolean maskable = pluginConfig.isMaskSensitive() && category.isMaskSensitive();
        Sanitizer.Result safeTitle = Sanitizer.mask(cleanTitle, maskable);
        Sanitizer.Result safeBody = Sanitizer.mask(cleanBody, maskable);
        if (safeTitle.isMasked() || safeBody.isMasked()) {
            send(player, "submit.mask-notice");
        }

        return submitValidated(player, category, safeTitle.getText(), safeBody.getText());
    }

    /**
     * 校验通过后的下一步：先看有没有人提过一样的问题，再真正投递。
     * <p>
     * 重复检测要打 GitHub API，所以放到异步线程；结果回到主线程后
     * 要么直接投递，要么弹一个「可能重复，仍要提交吗」的确认框。
     *
     * @return 恒为 true（校验已通过，后续是异步流程）
     */
    private boolean submitValidated(final Player player, final Category category,
                                    final String title, final String body) {
        if (!pluginConfig.isDuplicateCheck() || !gitHubApi.isAvailable()) {
            submit(player, category, title, body);
            return true;
        }

        send(player, "submit.checking-duplicate");
        final UUID playerId = player.getUniqueId();
        getServer().getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                final GitHubApi.IssueSummary similar = findSimilar(stripTitlePrefix(title));
                getServer().getScheduler().runTask(AqIssuePlugin.this, new Runnable() {
                    @Override
                    public void run() {
                        Player online = Bukkit.getPlayer(playerId);
                        if (online == null || !online.isOnline()) {
                            return;
                        }
                        if (similar == null) {
                            submit(online, category, title, body);
                            return;
                        }
                        send(online, "submit.duplicate-found",
                                "number", similar.number, "title", similar.title);
                        feedbackDialog.openDuplicate(online, category, title, body,
                                similar.number, similar.title, similar.url);
                    }
                });
            }
        });
        return true;
    }

    // ------------------------------------------------------------------
    // 重复检测
    // ------------------------------------------------------------------

    /**
     * 在最近未关闭的 Issue 里找一条和该标题最相似的（异步线程调用）。
     *
     * @return 没有足够相似的返回 {@code null}
     */
    private GitHubApi.IssueSummary findSimilar(String title) {
        String normalized = Similarity.normalize(title);
        if (normalized.isEmpty()) {
            return null;
        }
        List<GitHubApi.IssueSummary> issues = recentIssues();
        double threshold = pluginConfig.getDuplicateThreshold();

        GitHubApi.IssueSummary best = null;
        double bestScore = 0.0D;
        for (GitHubApi.IssueSummary issue : issues) {
            if (issue.number <= 0) {
                continue;
            }
            String other = Similarity.normalize(issue.title);
            if (!Similarity.isSimilar(normalized, other, threshold)) {
                continue;
            }
            double score = Similarity.dice(normalized, other);
            if (score >= bestScore) {
                bestScore = score;
                best = issue;
            }
        }
        return best;
    }

    /**
     * 最近未关闭的 Issue 列表，带 5 分钟缓存。
     * <p>
     * 只比对未关闭的：已经处理完的问题再提一次，通常是真的碰到了新问题。
     */
    private List<GitHubApi.IssueSummary> recentIssues() {
        List<GitHubApi.IssueSummary> cached = recentIssues;
        if (cached != null && System.currentTimeMillis() - recentIssuesAt < DUPLICATE_CACHE_MILLIS) {
            return cached;
        }
        synchronized (duplicateLock) {
            cached = recentIssues;
            if (cached != null && System.currentTimeMillis() - recentIssuesAt < DUPLICATE_CACHE_MILLIS) {
                return cached;
            }
            List<GitHubApi.IssueSummary> fetched;
            try {
                fetched = gitHubApi.listIssues("open", 50);
            } catch (Exception e) {
                getLogger().fine("读取已有反馈列表失败，本次跳过重复检测：" + e.getMessage());
                fetched = Collections.emptyList();
            }
            recentIssues = fetched;
            recentIssuesAt = System.currentTimeMillis();
            return fetched;
        }
    }

    /** 去掉标题前缀，避免前缀把相似度判断带偏。 */
    private String stripTitlePrefix(String title) {
        if (title == null) {
            return "";
        }
        String prefix = pluginConfig.getTitlePrefix();
        if (!prefix.isEmpty() && title.startsWith(prefix)) {
            return title.substring(prefix.length()).trim();
        }
        return title.trim();
    }

    /**
     * 该玩家是否豁免提交限制（冷却、长度校验）。
     * <p>
     * 默认给 {@code aqissue.admin}（也就是 OP）开绿灯，管理员测试、代提反馈时不用等冷却。
     */
    public boolean isBypassing(Player player) {
        String permission = pluginConfig.getBypassPermission();
        return !permission.isEmpty() && player.hasPermission(permission);
    }

    /**
     * 校验冷却时间。
     *
     * @return true 表示可以提交
     */
    public boolean checkCooldown(Player player) {
        if (isBypassing(player)) {
            return true;
        }
        long remaining = cooldownManager.remaining(player.getUniqueId(), pluginConfig.getCooldownSeconds());
        if (remaining > 0) {
            send(player, "submit.cooldown", "seconds", remaining);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    /**
     * 组装一条待投递的反馈。
     * <p>
     * 单独抽出来是因为重发队列也要用它 —— 那时玩家可能早就下线了，
     * 只能靠存下来的名字与 UUID 重建。
     *
     * @param title 不含标题前缀的纯标题
     */
    public Submission buildSubmission(String playerName, String playerUuid, Category category,
                                      String title, String body) {
        boolean withPlayer = pluginConfig.isIncludePlayerInfo();
        String fallbackLink = pluginConfig.isGitHubEnabled() && pluginConfig.isRepoConfigured()
                ? pluginConfig.getRepoUrl() : null;
        return new Submission(
                withPlayer ? playerName : null,
                withPlayer ? playerUuid : null,
                category.getId(),
                Text.plain(category.getName()),
                collectLabels(category),
                Text.oneLine(pluginConfig.getTitlePrefix() + title),
                body,
                pluginConfig.isIncludeServerInfo() ? Bukkit.getName() + " " + Bukkit.getVersion() : null,
                new Date(),
                fallbackLink);
    }

    /** 把反馈异步投递到所有已启用的渠道。 */
    public void submit(final Player player, final Category category, final String title, final String body) {
        send(player, "submit.submitting");

        final Submission submission = buildSubmission(player.getName(), player.getUniqueId().toString(),
                category, title, body);
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        channelManager.submitAll(submission, new java.util.function.Consumer<List<ChannelResult>>() {
            @Override
            public void accept(List<ChannelResult> results) {
                handleResults(playerId, playerName, category, submission, results);
            }
        });
    }

    private void handleResults(UUID playerId, String playerName, Category category,
                               Submission submission, List<ChannelResult> results) {
        boolean anySuccess = false;
        boolean queued = false;
        StringBuilder log = new StringBuilder();
        for (ChannelResult result : results) {
            if (result.isSuccess()) {
                anySuccess = true;
            }
            if (log.length() > 0) {
                log.append("；");
            }
            log.append(result.getDisplayName()).append(result.isSuccess() ? " 成功" : " 失败")
                    .append('(').append(result.getDetail()).append(')');
        }

        if (anySuccess) {
            cooldownManager.markSubmitted(playerId);
            trackIssue(submission, results);
        } else if (!results.isEmpty()) {
            // 全部失败多半是网络抖动，存起来等下一轮自动重试
            queued = feedbackQueue.enqueue(playerId.toString(), playerName,
                    category.getId(), submission.getCategoryName(),
                    stripTitlePrefix(submission.getTitle()), submission.getBody());
        }
        getLogger().info("玩家 " + playerName + "，分类 " + category.getId() + " -> " + log);

        Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()) {
            return;
        }
        if (results.isEmpty()) {
            send(player, "submit.no-channel");
            return;
        }

        player.sendMessage(lang.prefixed(anySuccess ? "submit.result-header-success" : "submit.all-failed"));
        for (ChannelResult result : results) {
            player.sendMessage(lang.text(result.isSuccess() ? "submit.result-line-ok" : "submit.result-line-fail",
                    "channel", result.getDisplayName(),
                    "detail", result.getDetail()));
        }
        if (queued) {
            send(player, "submit.queue-failed");
        }
    }

    /** 把刚创建成功的 Issue 交给跟踪器，用于后续的状态回传与超时提醒。 */
    public void trackIssue(Submission submission, List<ChannelResult> results) {
        if (submission == null || !pluginConfig.isTrackingEnabled()) {
            return;
        }
        for (ChannelResult result : results) {
            if (!result.isSuccess() || !GitHubChannel.ID.equals(result.getChannelId())) {
                continue;
            }
            String link = result.getLink();
            int number = parseIssueNumber(link);
            if (number > 0) {
                issueTracker.track(number, link,
                        submission.getPlayerUuid(), submission.getPlayerName(),
                        submission.getTitle(), submission.getCategoryName());
            }
        }
    }

    /** 从 Issue 链接末尾取编号。 */
    private static int parseIssueNumber(String link) {
        if (link == null) {
            return 0;
        }
        int index = link.lastIndexOf('/');
        if (index < 0 || index == link.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(link.substring(index + 1).trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private List<String> collectLabels(Category category) {
        Set<String> merged = new LinkedHashSet<String>();
        for (String label : pluginConfig.getLabels()) {
            if (label != null && !label.trim().isEmpty()) {
                merged.add(label.trim());
            }
        }
        for (String label : category.getLabels()) {
            if (label != null && !label.trim().isEmpty()) {
                merged.add(label.trim());
            }
        }
        return new ArrayList<String>(merged);
    }

    // ------------------------------------------------------------------
    // getter
    // ------------------------------------------------------------------

    public PluginConfig getPluginConfig() {
        return pluginConfig;
    }

    /** 语言文件（所有文本请从这里取）。 */
    public LangConfig getLang() {
        return lang;
    }

    public ChannelManager getChannelManager() {
        return channelManager;
    }

    /** GitHub REST 客户端（跟踪、重复检测、自检、关闭反馈共用）。 */
    public GitHubApi getGitHubApi() {
        return gitHubApi;
    }

    /** 反馈跟踪器（Issue 状态回传与超时提醒）。 */
    public IssueTracker getIssueTracker() {
        return issueTracker;
    }

    /** 投递失败后的重发队列。 */
    public FeedbackQueue getFeedbackQueue() {
        return feedbackQueue;
    }

    /** 记录并输出异常，避免异步任务里的异常被吞掉。 */
    public void logError(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }
}
