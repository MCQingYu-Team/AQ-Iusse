package cn.aqcraft.iusse;

import java.util.ArrayList;
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
import cn.aqcraft.iusse.channel.Submission;
import cn.aqcraft.iusse.command.IssueCommand;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.LangConfig;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.dialog.FeedbackDialog;
import cn.aqcraft.iusse.session.CooldownManager;
import cn.aqcraft.iusse.util.Text;

/**
 * AQIssue 主类：把游戏内的反馈同时投递到 GitHub / Discord / QQ 群。
 * <p>
 * 入口是 Paper 原生对话框，一次提交会广播到所有已启用的渠道。
 */
public class AqIssuePlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private LangConfig lang;
    private CooldownManager cooldownManager;
    private ChannelManager channelManager;
    private FeedbackDialog feedbackDialog;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.lang = new LangConfig(this);
        this.pluginConfig = new PluginConfig(this);
        this.cooldownManager = new CooldownManager();
        this.channelManager = new ChannelManager(this);
        this.feedbackDialog = new FeedbackDialog(this);

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
        if (!pluginConfig.hasToken() && pluginConfig.isGitHubEnabled() && !pluginConfig.isGitHubUsingApp()) {
            getLogger().warning("GitHub 通道既没有配置 App，也没有应急 PAT，该通道会投递失败。");
        }
        if (!pluginConfig.isGitHubEnabled() && !pluginConfig.isDiscordEnabled() && !pluginConfig.isOneBotEnabled()) {
            getLogger().warning("未启用任何投递渠道，请在 config.yml 的 channels 段中至少开启一个。");
        }
    }

    /** 重新读取配置与语言文件（/iusse reload）。 */
    public void reloadAll() {
        reloadConfig();
        pluginConfig.load();
        lang.load();
        cooldownManager.clear();
        channelManager.reload();
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

        String cleanTitle = Text.oneLine(Text.plain(title));
        if (cleanTitle.length() < pluginConfig.getMinTitleLength()) {
            send(player, "submit.title-too-short", "min", pluginConfig.getMinTitleLength());
            return false;
        }
        if (cleanTitle.length() > pluginConfig.getMaxTitleLength()) {
            send(player, "submit.title-too-long", "max", pluginConfig.getMaxTitleLength());
            return false;
        }

        String cleanBody = body == null ? "" : body.trim();
        if (cleanBody.isEmpty()) {
            send(player, "submit.body-empty");
            return false;
        }
        if (cleanBody.length() > pluginConfig.getMaxBodyLength()) {
            send(player, "submit.body-too-long", "max", pluginConfig.getMaxBodyLength());
            return false;
        }

        submit(player, category, cleanTitle, cleanBody);
        return true;
    }

    /**
     * 校验冷却时间。
     *
     * @return true 表示可以提交
     */
    public boolean checkCooldown(Player player) {
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

    /** 把反馈异步投递到所有已启用的渠道。 */
    public void submit(final Player player, final Category category, final String title, final String body) {
        send(player, "submit.submitting");

        boolean withPlayer = pluginConfig.isIncludePlayerInfo();
        Submission submission = new Submission(
                withPlayer ? player.getName() : null,
                withPlayer ? player.getUniqueId().toString() : null,
                category.getId(),
                Text.plain(category.getName()),
                collectLabels(category),
                Text.oneLine(pluginConfig.getTitlePrefix() + title),
                body,
                pluginConfig.isIncludeServerInfo() ? Bukkit.getName() + " " + Bukkit.getVersion() : null,
                new Date());

        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        channelManager.submitAll(submission, new java.util.function.Consumer<List<ChannelResult>>() {
            @Override
            public void accept(List<ChannelResult> results) {
                handleResults(playerId, playerName, category, results);
            }
        });
    }

    private void handleResults(UUID playerId, String playerName, Category category, List<ChannelResult> results) {
        boolean anySuccess = false;
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

    /** 记录并输出异常，避免异步任务里的异常被吞掉。 */
    public void logError(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }
}
