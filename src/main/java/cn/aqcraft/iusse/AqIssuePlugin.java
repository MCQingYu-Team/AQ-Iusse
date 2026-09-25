package cn.aqcraft.iusse;

import java.text.SimpleDateFormat;
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

import cn.aqcraft.iusse.command.IssueCommand;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.LangConfig;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.dialog.FeedbackDialog;
import cn.aqcraft.iusse.github.GitHubClient;
import cn.aqcraft.iusse.github.GitHubResult;
import cn.aqcraft.iusse.session.CooldownManager;
import cn.aqcraft.iusse.util.Text;

/**
 * AQIssue 主类：把游戏内的反馈直接变成 GitHub Issue。
 * <p>
 * 入口是 Paper 原生对话框（Dialog API），一份 jar 覆盖 Paper 1.21.7 ~ 最新版。
 */
public class AqIssuePlugin extends JavaPlugin {

    private PluginConfig pluginConfig;
    private LangConfig lang;
    private GitHubClient gitHubClient;
    private CooldownManager cooldownManager;
    private FeedbackDialog feedbackDialog;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.lang = new LangConfig(this);
        this.pluginConfig = new PluginConfig(this);
        this.gitHubClient = new GitHubClient(this);
        this.cooldownManager = new CooldownManager();
        this.feedbackDialog = new FeedbackDialog(this);

        PluginCommand command = getCommand("iusse");
        if (command != null) {
            IssueCommand executor = new IssueCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
            getLogger().info("指令注册成功：/" + command.getName()
                    + (command.getAliases().isEmpty() ? "" : "（别名 /" + String.join("、/", command.getAliases()) + "）"));
        } else {
            getLogger().severe("无法注册指令 /iusse —— plugin.yml 里的 commands 段可能与其他插件冲突。");
        }

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
        if (cooldownManager != null) {
            cooldownManager.clear();
        }
    }

    private void logStartupSummary() {
        getLogger().info("语言文件：" + lang.getFileName() + " (" + lang.getLocale() + ")");
        getLogger().info("已加载，目标仓库：" + pluginConfig.getRepoUrl());
        if (!pluginConfig.isRepoConfigured()) {
            getLogger().warning("github.owner / github.repo 未配置，提交功能不可用。");
        } else if (!pluginConfig.hasToken()) {
            getLogger().warning("尚未配置 github.token —— 只能向公开仓库提交，且极易触发 GitHub 匿名限流。");
        }
    }

    /** 重新读取配置与语言文件（/iusse reload）。 */
    public void reloadAll() {
        reloadConfig();
        pluginConfig.load();
        lang.load();
        cooldownManager.clear();
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
        try {
            feedbackDialog.open(player);
        } catch (Throwable throwable) {
            logError("打开反馈对话框失败", throwable);
            send(player, "dialog.unsupported");
        }
    }

    /**
     * 校验来自对话框或指令的输入，通过后异步提交。
     *
     * @return true 表示校验通过并已开始提交
     */
    public boolean submitFromInput(Player player, String categoryId, String title, String body) {
        Category category = pluginConfig.findCategory(categoryId);
        if (category == null) {
            send(player, "general.unknown-category", "id", categoryId);
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
    // 提交
    // ------------------------------------------------------------------

    /** 把内容异步提交到 GitHub。 */
    public void submit(final Player player, final Category category, final String title, final String body) {
        send(player, "submit.submitting");

        final String issueTitle = Text.oneLine(pluginConfig.getTitlePrefix() + title);
        final String issueBody = buildIssueBody(player, category, body);
        final List<String> labels = collectLabels(category);
        final UUID playerId = player.getUniqueId();
        final String playerName = player.getName();
        final String categoryId = category.getId();

        Bukkit.getScheduler().runTaskAsynchronously(this, new Runnable() {
            @Override
            public void run() {
                final GitHubResult result = gitHubClient.createIssue(issueTitle, issueBody, labels);
                Bukkit.getScheduler().runTask(AqIssuePlugin.this, new Runnable() {
                    @Override
                    public void run() {
                        handleResult(playerId, playerName, categoryId, result);
                    }
                });
            }
        });
    }

    private void handleResult(UUID playerId, String playerName, String categoryId, GitHubResult result) {
        Player player = Bukkit.getPlayer(playerId);

        if (result.isSuccess()) {
            cooldownManager.markSubmitted(playerId);
            getLogger().info("玩家 " + playerName + " 提交 Issue #" + result.getIssueNumber()
                    + "（分类 " + categoryId + "）：" + result.getIssueUrl());
            if (player != null && player.isOnline()) {
                player.sendMessage(lang.prefixed("submit.success",
                        "number", result.getIssueNumber(),
                        "url", result.getIssueUrl()));
            }
            return;
        }

        getLogger().warning("玩家 " + playerName + " 提交 Issue 失败：" + result.getMessage());
        if (player != null && player.isOnline()) {
            player.sendMessage(lang.prefixed("submit.failed", "reason", result.getMessage()));
        }
    }

    /** 组装 Issue 正文：可选的玩家信息 + 反馈分类 + 服务器信息 + 正文。 */
    private String buildIssueBody(Player player, Category category, String body) {
        StringBuilder builder = new StringBuilder();

        if (pluginConfig.isIncludePlayerInfo()) {
            builder.append("- **提交玩家**：").append(player.getName())
                    .append(" (`").append(player.getUniqueId()).append("`)\n");
        }
        builder.append("- **反馈分类**：").append(category.getName())
                .append(" (`").append(category.getId()).append("`)\n");
        if (pluginConfig.isIncludeServerInfo()) {
            builder.append("- **服务端**：").append(Bukkit.getName())
                    .append(' ').append(Bukkit.getVersion()).append("\n");
        }
        builder.append("- **提交时间**：")
                .append(new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new Date()))
                .append("\n\n---\n\n");
        builder.append(body);
        return builder.toString();
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

    public GitHubClient getGitHubClient() {
        return gitHubClient;
    }

    /** 记录并输出异常，避免异步任务里的异常被吞掉。 */
    public void logError(String message, Throwable throwable) {
        getLogger().log(Level.SEVERE, message, throwable);
    }
}
