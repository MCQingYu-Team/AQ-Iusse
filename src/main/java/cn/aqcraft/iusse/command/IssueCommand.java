package cn.aqcraft.iusse.command;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.channel.Channel;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.GitHubApi;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.integration.EasyBotBridge;
import cn.aqcraft.iusse.tracking.IssueRecord;
import cn.aqcraft.iusse.tracking.IssueStats;
import cn.aqcraft.iusse.util.Text;

/**
 * {@code /iusse} 指令。
 * <ul>
 *   <li>无参数 —— 打开反馈对话框</li>
 *   <li>{@code submit <分类> <标题>|<内容>} —— 一行式提交，适合快捷键 / 脚本</li>
 *   <li>{@code mine} —— 查看自己提交过的反馈与处理进度</li>
 *   <li>{@code reply <编号> <内容>} —— 给自己的反馈补充说明</li>
 *   <li>{@code url} / {@code help}</li>
 *   <li>管理员：{@code list} / {@code close} / {@code stats} / {@code test} / {@code qq} / {@code status} / {@code reload}</li>
 * </ul>
 */
public class IssueCommand implements CommandExecutor, TabCompleter {

    private static final String USAGE_SUBMIT = "/iusse submit <分类> <标题>|<内容>";
    private static final String USAGE_REPLY = "/iusse reply <编号> <内容>";
    private static final String USAGE_CLOSE = "/iusse close <编号> [理由]";
    private static final String USAGE_QQ = "/iusse qq <玩家> [测试消息]";
    private static final String USAGE_LIST = "/iusse list [open|closed|all]";

    private static final List<String> SUB_COMMANDS =
            Arrays.asList("submit", "mine", "reply", "url", "status", "list", "close", "stats", "test", "qq",
                    "reload", "help");

    /** 只有管理员能用的子指令。 */
    private static final List<String> ADMIN_SUB_COMMANDS =
            Arrays.asList("status", "list", "close", "stats", "test", "qq", "reload");

    private final AqIssuePlugin plugin;

    public IssueCommand(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player)) {
            sender.sendMessage(plugin.getLang().text("general.player-only"));
            return true;
        }
        Player player = (Player) sender;

        if (args.length == 0) {
            plugin.openDialog(player);
            return true;
        }

        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "help":
            case "?":
                sendUsage(player);
                return true;
            case "submit":
                handleSubmit(player, args);
                return true;
            case "mine":
                handleMine(player);
                return true;
            case "reply":
                handleReply(player, args);
                return true;
            case "url":
                plugin.send(player, "repository.url", "url", plugin.getPluginConfig().getRepoUrl());
                return true;
            case "status":
                if (requireAdmin(player)) {
                    handleStatus(player);
                }
                return true;
            case "list":
                if (requireAdmin(player)) {
                    handleList(player, args);
                }
                return true;
            case "close":
                if (requireAdmin(player)) {
                    handleClose(player, args);
                }
                return true;
            case "stats":
                if (requireAdmin(player)) {
                    handleStats(player);
                }
                return true;
            case "test":
                if (requireAdmin(player)) {
                    handleTest(player);
                }
                return true;
            case "qq":
                if (requireAdmin(player)) {
                    handleQq(player, args);
                }
                return true;
            case "reload":
                if (requireAdmin(player)) {
                    plugin.reloadAll();
                    plugin.send(player, "general.reloaded");
                }
                return true;
            default:
                sendUsage(player);
                return true;
        }
    }

    // ------------------------------------------------------------------

    private void handleSubmit(Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_SUBMIT);
            return;
        }
        String categoryId = args[1];
        String rest = join(args, 2);
        int separator = rest.indexOf('|');
        if (separator < 0) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_SUBMIT);
            return;
        }
        String title = rest.substring(0, separator).trim();
        String body = rest.substring(separator + 1).trim();
        plugin.submitFromInput(player, categoryId, title, body);
    }

    // ------------------------------------------------------------------
    // 玩家侧：我的反馈 / 回复
    // ------------------------------------------------------------------

    /** {@code /iusse mine} —— 列出自己提交过的反馈与处理进度。 */
    private void handleMine(Player player) {
        final String uuid = player.getUniqueId().toString();
        List<IssueRecord> mine = new ArrayList<IssueRecord>();
        for (IssueRecord record : plugin.getIssueTracker().all()) {
            if (uuid.equals(record.playerUuid)) {
                mine.add(record);
            }
        }
        if (mine.isEmpty()) {
            plugin.send(player, "feedback.mine-empty");
            return;
        }
        // 最近提交的排最前
        mine.sort((left, right) -> Long.compare(right.submittedAt, left.submittedAt));

        int limit = Math.min(mine.size(), 10);
        player.sendMessage(plugin.getLang().prefixed("feedback.mine-header",
                "count", limit, "total", mine.size()));

        long now = System.currentTimeMillis();
        for (int index = 0; index < limit; index++) {
            IssueRecord record = mine.get(index);
            player.sendMessage(plugin.getLang().text(
                    record.closed ? "feedback.mine-line-closed" : "feedback.mine-line-open",
                    "number", record.number,
                    "title", Text.truncate(record.title, 24),
                    "category", record.categoryName,
                    "age", IssueStats.hoursSince(record.submittedAt, now)));
        }
        player.sendMessage(plugin.getLang().text("feedback.mine-hint"));
    }

    /** {@code /iusse reply <编号> <内容>} —— 给自己的反馈补充说明。 */
    private void handleReply(final Player player, String[] args) {
        if (args.length < 3) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_REPLY);
            return;
        }
        final int number = parseNumber(args[1]);
        if (number <= 0) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_REPLY);
            return;
        }
        final String content = join(args, 2).trim();
        if (content.isEmpty()) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_REPLY);
            return;
        }
        int maxLength = plugin.getPluginConfig().getMaxReplyLength();
        if (content.length() > maxLength) {
            plugin.send(player, "reply.too-long", "max", maxLength);
            return;
        }

        final IssueRecord record = plugin.getIssueTracker().find(number);
        if (record == null) {
            plugin.send(player, "reply.not-found", "number", number);
            return;
        }
        if (!player.hasPermission("aqissue.admin") && !player.getUniqueId().toString().equals(record.playerUuid)) {
            plugin.send(player, "reply.not-owner");
            return;
        }
        if (!plugin.getGitHubApi().isAvailable()) {
            plugin.send(player, "general.github-unavailable");
            return;
        }

        plugin.send(player, "reply.sending", "number", number);
        final String author = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    plugin.getGitHubApi().addComment(number,
                            "**" + author + "**（游戏内回复）：\n\n" + content);
                    plugin.getIssueTracker().markRepliedLocally(number);
                } catch (IOException e) {
                    failure = e.getMessage();
                }
                final String detail = failure;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (detail == null) {
                            plugin.send(player, "reply.success", "number", number, "title", record.title);
                        } else {
                            plugin.send(player, "reply.failed", "detail", detail);
                        }
                    }
                });
            }
        });
    }

    // ------------------------------------------------------------------
    // 管理员侧
    // ------------------------------------------------------------------

    /** {@code /iusse list [open|closed|all]}。 */
    private void handleList(Player player, String[] args) {
        String mode = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "open";
        if (!"open".equals(mode) && !"closed".equals(mode) && !"all".equals(mode)) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_LIST);
            return;
        }

        List<IssueRecord> list = new ArrayList<IssueRecord>();
        for (IssueRecord record : plugin.getIssueTracker().all()) {
            if ("closed".equals(mode)) {
                if (record.closed) {
                    list.add(record);
                }
            } else if ("all".equals(mode)) {
                list.add(record);
            } else if (!record.closed) {
                list.add(record);
            }
        }
        if (list.isEmpty()) {
            plugin.send(player, "list.empty");
            return;
        }
        // 等得最久的最该被看到，排最前
        list.sort((left, right) -> Long.compare(left.submittedAt, right.submittedAt));

        int limit = Math.min(list.size(), 15);
        player.sendMessage(plugin.getLang().prefixed("list.header",
                "count", limit, "total", list.size()));

        long now = System.currentTimeMillis();
        long slaMillis = plugin.getPluginConfig().getSlaHours() * 3600000L;
        for (int index = 0; index < limit; index++) {
            IssueRecord record = list.get(index);
            if (record.closed) {
                player.sendMessage(plugin.getLang().text("list.line-closed",
                        "number", record.number,
                        "title", Text.truncate(record.title, 24),
                        "category", record.categoryName,
                        "player", record.playerName));
                continue;
            }
            boolean overdue = record.submittedAt > 0 && now - record.submittedAt > slaMillis;
            player.sendMessage(plugin.getLang().text(overdue ? "list.line-overdue" : "list.line",
                    "number", record.number,
                    "title", Text.truncate(record.title, 24),
                    "category", record.categoryName,
                    "player", record.playerName,
                    "age", IssueStats.hoursSince(record.submittedAt, now)));
        }
        player.sendMessage(plugin.getLang().text("list.hint"));
    }

    /** {@code /iusse close <编号> [理由]} —— 不用离开游戏就能处理掉一条反馈。 */
    private void handleClose(final Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_CLOSE);
            return;
        }
        final int number = parseNumber(args[1]);
        if (number <= 0) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_CLOSE);
            return;
        }
        final String reason = args.length >= 3 ? join(args, 2).trim() : "";

        final IssueRecord record = plugin.getIssueTracker().find(number);
        if (record == null) {
            plugin.send(player, "close.not-found", "number", number);
            return;
        }
        if (!plugin.getGitHubApi().isAvailable()) {
            plugin.send(player, "general.github-unavailable");
            return;
        }

        plugin.send(player, "close.sending", "number", number);
        final String operator = player.getName();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                String failure = null;
                try {
                    GitHubApi api = plugin.getGitHubApi();
                    if (!reason.isEmpty()) {
                        api.addComment(number,
                                "**" + operator + "**（游戏内）将其标记为已处理：\n\n" + reason);
                    }
                    api.closeIssue(number);
                    plugin.getIssueTracker().markClosedLocally(number);
                    plugin.getIssueTracker().announceClosedLocally(number);
                } catch (IOException e) {
                    failure = e.getMessage();
                }
                final String detail = failure;
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (detail == null) {
                            plugin.send(player, "close.success", "number", number);
                        } else {
                            plugin.send(player, "close.failed", "detail", detail);
                        }
                    }
                });
            }
        });
    }

    /** {@code /iusse stats} —— 全部基于本地记录，不消耗 API 额度。 */
    private void handleStats(Player player) {
        IssueStats stats = IssueStats.compute(plugin.getIssueTracker().all(),
                plugin.getPluginConfig().getSlaHours(), System.currentTimeMillis());

        player.sendMessage(plugin.getLang().prefixed("stats.header"));
        player.sendMessage(plugin.getLang().text("stats.line-total", "value", stats.total));
        player.sendMessage(plugin.getLang().text("stats.line-open", "value", stats.open));
        player.sendMessage(plugin.getLang().text("stats.line-closed", "value", stats.closed));
        player.sendMessage(plugin.getLang().text("stats.line-overdue", "value", stats.overdue));
        player.sendMessage(plugin.getLang().text("stats.line-average",
                "value", IssueStats.formatDuration(stats.averageHandleMillis)));
        player.sendMessage(plugin.getLang().text("stats.line-recent", "value", stats.last7Days));
        player.sendMessage(plugin.getLang().text("stats.line-queue",
                "value", plugin.getFeedbackQueue().size()));
    }

    /**
     * {@code /iusse test} —— 一站式自检。
     * <p>
     * 比 {@code status} 多做三件事：把未启用的渠道也列出来并说明原因、
     * 检查配置里的标签在仓库里是否真的存在、报告 EasyBot 与重发队列状态。
     * 网络检查全在异步线程完成，结果回到主线程再翻译成语言文件里的文案。
     */
    private void handleTest(final Player player) {
        plugin.send(player, "test.checking");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                // [级别, 名称, 详情]，主线程再套文案
                final List<String[]> rows = new ArrayList<String[]>();
                collectGitHubChecks(rows);
                collectChannelChecks(rows);
                collectIntegrationChecks(rows);

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        player.sendMessage(plugin.getLang().prefixed("test.header"));
                        for (String[] row : rows) {
                            player.sendMessage(localized(row));
                        }
                    }
                });
            }
        });
    }

    private void collectGitHubChecks(List<String[]> rows) {
        GitHubApi api = plugin.getGitHubApi();
        if (!api.isAvailable()) {
            rows.add(new String[]{"fail", "GitHub", "未启用，或未配置 PAT / owner / repo"});
            return;
        }
        try {
            Map<String, Object> repo = api.getRepo();
            String fullName = MiniJson.string(repo, "full_name");
            rows.add(new String[]{"ok", "GitHub", "PAT 有效｜仓库 " + (fullName == null ? "?" : fullName)
                    + "｜剩余额度 " + api.getLastRateRemaining()});
        } catch (IOException e) {
            rows.add(new String[]{"fail", "GitHub", e.getMessage()});
            return;
        }

        try {
            List<String> existing = api.listLabels();
            Set<String> missing = new LinkedHashSet<String>();
            for (String label : plugin.getPluginConfig().getLabels()) {
                if (label != null && !label.trim().isEmpty() && !existing.contains(label.trim())) {
                    missing.add(label.trim());
                }
            }
            for (Category category : plugin.getPluginConfig().getCategories()) {
                for (String label : category.getLabels()) {
                    if (label != null && !label.trim().isEmpty() && !existing.contains(label.trim())) {
                        missing.add(label.trim());
                    }
                }
            }
            if (missing.isEmpty()) {
                rows.add(new String[]{"ok", "标签", "配置中的标签在仓库里都存在"});
            } else {
                rows.add(new String[]{"warn", "缺失标签", String.join("、", missing)
                        + "（GitHub 会静默忽略未创建的标签）"});
            }
        } catch (IOException e) {
            rows.add(new String[]{"warn", "标签", "读取标签列表失败：" + e.getMessage()});
        }
    }

    private void collectChannelChecks(List<String[]> rows) {
        for (Channel channel : plugin.getChannelManager().getAllChannels()) {
            if (!channel.isEnabled()) {
                rows.add(new String[]{"warn", channel.getDisplayName(), "未启用：" + channel.getDisabledReason()});
                continue;
            }
            try {
                rows.add(new String[]{"ok", channel.getDisplayName(), channel.checkStatus()});
            } catch (Throwable throwable) {
                rows.add(new String[]{"fail", channel.getDisplayName(), describe(throwable)});
            }
        }
    }

    private void collectIntegrationChecks(List<String[]> rows) {
        if (EasyBotBridge.isAvailable() && EasyBotBridge.isReady()) {
            rows.add(new String[]{"ok", "EasyBot", "已就绪，可用 /iusse qq <玩家> 查询绑定的 QQ"});
        } else if (EasyBotBridge.isAvailable()) {
            rows.add(new String[]{"warn", "EasyBot",
                    "插件已加载，但还没连上 EasyBot 主程序 —— 查绑定会失败"});
        } else {
            rows.add(new String[]{"fail", "EasyBot", EasyBotBridge.describePluginPresence()
                    + "；" + EasyBotBridge.getFailureReason()});
        }
        rows.add(new String[]{"ok", "反馈跟踪", "已记录 " + plugin.getIssueTracker().size()
                + " 条｜待重发 " + plugin.getFeedbackQueue().size() + " 条"});
    }

    private String localized(String[] row) {
        String key;
        switch (row[0]) {
            case "ok":
                key = "test.ok";
                break;
            case "warn":
                key = "test.warn";
                break;
            default:
                key = "test.fail";
                break;
        }
        return plugin.getLang().text(key, "name", row[1], "detail", row[2]);
    }

    /**
     * {@code /iusse qq <玩家> [测试消息]} —— 查询玩家绑定的 QQ，并可选发一条测试私信。
     * <p>
     * 排查 QQ 通知链路时最有用的一条指令：能一次看清
     * 「EasyBot 在不在 → 玩家绑没绑 → OneBot 发得出去吗」。
     */
    private void handleQq(final Player player, String[] args) {
        if (args.length < 2) {
            plugin.send(player, "command.invalid-syntax", "usage", USAGE_QQ);
            return;
        }
        final String target = args[1];
        final String message = args.length >= 3 ? join(args, 2).trim() : "";

        plugin.send(player, "qq.checking", "player", target);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final boolean easybot = EasyBotBridge.isAvailable();
                final boolean ready = easybot && EasyBotBridge.isReady();
                final String easybotDetail = EasyBotBridge.getFailureReason();
                long qq = ready ? EasyBotBridge.queryQq(target) : 0L;

                boolean sent = false;
                String sendError = null;
                if (qq > 0 && !message.isEmpty()) {
                    for (Channel channel : plugin.getChannelManager().getChannels()) {
                        try {
                            if (channel.sendPrivate(qq, "【AQIssue 测试】" + message)) {
                                sent = true;
                                break;
                            }
                        } catch (Throwable throwable) {
                            sendError = describe(throwable);
                        }
                    }
                }

                final long account = qq;
                final boolean delivered = sent;
                final String failure = sendError;
                final boolean wantsTest = !message.isEmpty();

                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        if (!easybot) {
                            plugin.send(player, "qq.easybot-missing", "detail", easybotDetail);
                            return;
                        }
                        if (!ready) {
                            plugin.send(player, "qq.easybot-not-ready");
                            return;
                        }
                        if (account <= 0) {
                            plugin.send(player, "qq.unbound", "player", target);
                            return;
                        }
                        plugin.send(player, "qq.bound", "player", target, "qq", account);
                        if (!wantsTest) {
                            return;
                        }
                        if (delivered) {
                            plugin.send(player, "qq.sent", "qq", account);
                        } else {
                            plugin.send(player, "qq.send-failed",
                                    "detail", failure == null ? "没有任何可用的 OneBot 连接" : failure);
                        }
                    }
                });
            }
        });
    }

    private void handleStatus(final Player player) {
        plugin.send(player, "status.checking");
        final List<Channel> channels = plugin.getChannelManager().getChannels();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final List<String> lines = new ArrayList<String>();
                for (Channel channel : channels) {
                    try {
                        lines.add(plugin.getLang().text("status.channel-ok",
                                "channel", channel.getDisplayName(),
                                "detail", channel.checkStatus()));
                    } catch (Throwable throwable) {
                        String message = throwable.getMessage();
                        lines.add(plugin.getLang().text("status.channel-fail",
                                "channel", channel.getDisplayName(),
                                "detail", message == null ? throwable.getClass().getSimpleName() : message));
                    }
                }
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        if (!player.isOnline()) {
                            return;
                        }
                        player.sendMessage(plugin.getLang().prefixed("status.header"));
                        if (lines.isEmpty()) {
                            player.sendMessage(plugin.getLang().text("status.no-channel"));
                            return;
                        }
                        for (String line : lines) {
                            player.sendMessage(line);
                        }
                    }
                });
            }
        });
    }

    private boolean requireAdmin(Player player) {
        if (player.hasPermission("aqissue.admin")) {
            return true;
        }
        plugin.send(player, "general.no-permission");
        return false;
    }

    private void sendUsage(Player player) {
        for (String line : plugin.getLang().usageLines()) {
            player.sendMessage(line);
        }
    }

    private static String join(String[] args, int from) {
        StringBuilder builder = new StringBuilder();
        for (int index = from; index < args.length; index++) {
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(args[index]);
        }
        return builder.toString();
    }

    /** 解析 Issue 编号，支持 {@code #12} 这种写法。 */
    private static int parseNumber(String raw) {
        if (raw == null) {
            return 0;
        }
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String describe(Throwable throwable) {
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }

    // ------------------------------------------------------------------

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        List<String> result = new ArrayList<String>();

        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            boolean admin = sender.hasPermission("aqissue.admin");
            for (String sub : SUB_COMMANDS) {
                if (!admin && ADMIN_SUB_COMMANDS.contains(sub)) {
                    continue;
                }
                if (sub.startsWith(prefix)) {
                    result.add(sub);
                }
            }
            return result;
        }

        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            String sub = args[0].toLowerCase(Locale.ROOT);

            if ("submit".equals(sub)) {
                PluginConfig config = plugin.getPluginConfig();
                for (Category category : config.getCategories()) {
                    if (category.getId().startsWith(prefix)) {
                        result.add(category.getId());
                    }
                }
                return result;
            }
            if ("list".equals(sub)) {
                for (String mode : Arrays.asList("open", "closed", "all")) {
                    if (mode.startsWith(prefix)) {
                        result.add(mode);
                    }
                }
                return result;
            }
            if ("qq".equals(sub)) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (online.getName().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                        result.add(online.getName());
                    }
                }
                return result;
            }
            if ("reply".equals(sub) || "close".equals(sub)) {
                // reply 只提示自己提过的，close 提示全部未处理的
                String self = sender instanceof Player ? ((Player) sender).getUniqueId().toString() : "";
                for (IssueRecord record : plugin.getIssueTracker().all()) {
                    if ("reply".equals(sub) && !self.equals(record.playerUuid)
                            && !sender.hasPermission("aqissue.admin")) {
                        continue;
                    }
                    if ("close".equals(sub) && record.closed) {
                        continue;
                    }
                    String number = String.valueOf(record.number);
                    if (number.startsWith(prefix)) {
                        result.add(number);
                    }
                }
                return result;
            }
        }

        return result;
    }
}
