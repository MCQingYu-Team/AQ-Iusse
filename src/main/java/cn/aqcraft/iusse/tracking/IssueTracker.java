package cn.aqcraft.iusse.tracking;

import java.io.File;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.channel.Channel;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.GitHubApi;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.integration.EasyBotBridge;
import cn.aqcraft.iusse.util.Text;

/**
 * 反馈跟踪：定时查询 GitHub Issue 的状态。
 * <p>
 * 负责两件事：
 * <ul>
 *   <li><b>状态回传</b>：Issue 被关闭或有新评论时，通知当初提交的玩家（离线则排队，上线补发）</li>
 *   <li><b>超时提醒（SLA）</b>：Issue 长时间未处理时，提醒管理员（控制台 + 已启用的渠道）</li>
 * </ul>
 * 跟踪记录落在 {@code plugins/AQIssue/issues.json}，重启后不会重复通知。
 */
public class IssueTracker {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "issues.json";
    private static final long HOUR_MILLIS = 3600000L;
    private static final long DAY_MILLIS = 86400000L;
    /** 一分钟对应的 tick 数。 */
    private static final long TICKS_PER_MINUTE = 1200L;

    private final AqIssuePlugin plugin;
    private final List<IssueRecord> records = new CopyOnWriteArrayList<IssueRecord>();
    /** 玩家离线期间攒下的通知，上线后补发。 */
    private final Map<UUID, List<String>> pending = new ConcurrentHashMap<UUID, List<String>>();

    private File file;
    private int taskId = -1;
    /** 额度不足时还需要跳过几轮，用来把轮询间隔临时放大。 */
    private int throttleCountdown;
    /** 额度不足的告警是否已经发过，避免每轮刷屏。 */
    private boolean throttleWarned;

    public IssueTracker(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    /** 载入历史记录并按配置排期。 */
    public void start() {
        this.file = new File(plugin.getDataFolder(), FILE_NAME);
        load();
        reschedule();
    }

    public void stop() {
        cancelTask();
        save();
    }

    /** 配置变更后重新排期（reload 时调用）。 */
    public void reschedule() {
        cancelTask();
        PluginConfig config = plugin.getPluginConfig();
        if (!config.isTrackingEnabled()) {
            return;
        }
        long interval = Math.max(1, config.getTrackingIntervalMinutes()) * TICKS_PER_MINUTE;
        this.taskId = Bukkit.getScheduler()
                .runTaskTimerAsynchronously(plugin, new Runnable() {
                    @Override
                    public void run() {
                        poll();
                    }
                }, interval, interval)
                .getTaskId();
        plugin.getLogger().info("反馈跟踪已启动：每 " + config.getTrackingIntervalMinutes()
                + " 分钟检查一次 Issue 状态（已载入 " + records.size() + " 条记录）");
    }

    private void cancelTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    // ------------------------------------------------------------------
    // 登记与查询
    // ------------------------------------------------------------------

    /** 登记一条刚创建成功的 Issue。 */
    public void track(int number, String url, String playerUuid, String playerName,
                      String title, String categoryName, String priorityName) {
        if (number <= 0) {
            return;
        }
        for (IssueRecord existing : records) {
            if (existing.number == number) {
                return;
            }
        }
        IssueRecord record = new IssueRecord(number, url, playerName, playerUuid, title, categoryName,
                System.currentTimeMillis());
        if (priorityName != null) {
            record.priorityName = priorityName;
        }
        records.add(record);
        save();
    }

    public int size() {
        return records.size();
    }

    /** 玩家离线期间积压的通知条数。 */
    public int pendingCount(Player player) {
        List<String> messages = pending.get(player.getUniqueId());
        return messages == null ? 0 : messages.size();
    }

    /** 玩家上线时补发离线期间的通知。 */
    public void flushPending(Player player) {
        List<String> messages = pending.remove(player.getUniqueId());
        if (messages == null || messages.isEmpty()) {
            return;
        }
        for (String message : messages) {
            player.sendMessage(message);
        }
    }

    // ------------------------------------------------------------------
    // 轮询
    // ------------------------------------------------------------------

    private void poll() {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.isTrackingEnabled()) {
            return;
        }
        // 查询 Issue 状态必须有 GitHub 凭据
        if (!plugin.getGitHubApi().isAvailable()) {
            return;
        }
        // API 额度快用完了就自动降频：跳过接下来几轮，把间隔临时放大 6 倍
        if (throttleIfNeeded(config)) {
            return;
        }

        purgeExpired(config);

        long cutoff = System.currentTimeMillis() - config.getTrackingKeepDays() * DAY_MILLIS;
        List<IssueRecord> targets = new ArrayList<IssueRecord>();
        for (IssueRecord record : records) {
            if (record.closed && record.closeNotified) {
                continue; // 已经处理完，不必再查
            }
            if (record.submittedAt < cutoff) {
                continue;
            }
            targets.add(record);
            if (targets.size() >= config.getTrackingMaxPerRun()) {
                break;
            }
        }

        boolean changed = false;
        for (IssueRecord record : targets) {
            changed |= check(record);
        }
        if (changed) {
            save();
        }
    }

    /**
     * API 额度快用完时自动降低轮询频率。
     * <p>
     * 额度耗尽时跟踪会静默停摆（每次查询都 403），比起那样，
     * 不如主动把间隔临时放大 6 倍，把额度留给「提交反馈」这件更要紧的事。
     *
     * @return true 表示本轮应当跳过
     */
    private boolean throttleIfNeeded(PluginConfig config) {
        int remaining = plugin.getGitHubApi().getLastRateRemaining();
        int threshold = config.getRateLimitThreshold();
        if (threshold <= 0 || remaining < 0 || remaining >= threshold) {
            throttleWarned = false;
            throttleCountdown = 0;
            return false;
        }
        if (throttleCountdown > 0) {
            throttleCountdown--;
            return true;
        }
        throttleCountdown = 5;
        if (!throttleWarned) {
            throttleWarned = true;
            plugin.getLogger().warning("GitHub API 剩余额度仅 " + remaining + " 次（低于 "
                    + threshold + "），已自动降低反馈状态轮询频率，额度恢复后自动还原。");
        }
        return false;
    }

    /** 清理太久以前的记录，避免文件无限增长。 */
    private void purgeExpired(PluginConfig config) {
        long purgeBefore = System.currentTimeMillis() - config.getTrackingKeepDays() * 2L * DAY_MILLIS;
        records.removeIf(record -> record.submittedAt > 0 && record.submittedAt < purgeBefore);
    }

    /** 查询单条 Issue 的最新状态。
     *
     * @return 记录是否发生变化（需要落盘）
     */
    private boolean check(IssueRecord record) {
        PluginConfig config = plugin.getPluginConfig();

        GitHubApi.IssueSummary summary;
        try {
            summary = plugin.getGitHubApi().getIssue(record.number);
        } catch (IOException e) {
            plugin.getLogger().fine("查询 Issue #" + record.number + " 失败：" + e.getMessage());
            return false;
        }
        if (summary.number <= 0) {
            return false;
        }

        if (summary.isClosed()) {
            if (record.closeNotified) {
                return false;
            }
            record.closed = true;
            record.closeNotified = true;
            record.closedAt = System.currentTimeMillis();
            notifyPlayer(record, plugin.getLang().prefixed("tracking.notify-closed",
                    "title", record.title,
                    "url", record.url));
            if (config.isTrackingAnnounceOnClose()) {
                announceClosed(record);
            }
            return true;
        }

        boolean changed = false;

        if (summary.comments > record.commentCount) {
            record.commentCount = summary.comments;
            changed = true;
            if (config.isNotifyOnComment()) {
                String comment = fetchLastComment(record.number);
                if (comment != null && !comment.isEmpty()) {
                    notifyPlayer(record, plugin.getLang().prefixed("tracking.notify-comment",
                            "title", record.title,
                            "comment", Text.truncate(comment, 200),
                            "url", record.url));
                }
            }
        }

        if (checkSla(record)) {
            changed = true;
        }
        return changed;
    }

    /**
     * 取 Issue 的最后一条评论，形如 {@code 用户名：内容}。
     * <p>
     * 返回列表按创建时间正序（GitHub 的默认顺序），所以最后一条就是最新的。
     */
    private String fetchLastComment(int number) {
        try {
            List<Map<String, Object>> comments = plugin.getGitHubApi().listComments(number, 100);
            if (comments.isEmpty()) {
                return null;
            }
            Map<String, Object> last = comments.get(comments.size() - 1);
            String body = MiniJson.string(last, "body");
            if (body == null || body.isEmpty()) {
                return null;
            }
            String login = null;
            Object user = last.get("user");
            if (user instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userMap = (Map<String, Object>) user;
                login = MiniJson.string(userMap, "login");
            }
            return login == null || login.isEmpty() ? body : login + "：" + body;
        } catch (IOException e) {
            plugin.getLogger().fine("获取 Issue #" + number + " 评论失败：" + e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // SLA
    // ------------------------------------------------------------------

    /** @return 是否刚刚发出了一次超时提醒 */
    private boolean checkSla(IssueRecord record) {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.isSlaEnabled()) {
            return false;
        }
        long age = System.currentTimeMillis() - record.submittedAt;
        if (age < config.getSlaHours() * HOUR_MILLIS) {
            return false;
        }
        long repeat = Math.max(1, config.getSlaRepeatHours()) * HOUR_MILLIS;
        if (record.lastSlaNotice > 0 && System.currentTimeMillis() - record.lastSlaNotice < repeat) {
            return false;
        }
        record.lastSlaNotice = System.currentTimeMillis();
        announceSla(record, age / HOUR_MILLIS);
        return true;
    }

    private void announceSla(IssueRecord record, long ageHours) {
        PluginConfig config = plugin.getPluginConfig();
        plugin.getLogger().warning("反馈 #" + record.number + "「" + record.title
                + "」已提交 " + ageHours + " 小时仍未处理：" + record.url);

        if (!config.isSlaBroadcast()) {
            return;
        }
        // 渠道不认 MC 颜色代码，这里用去色文本
        broadcast(plugin.getLang().plain("sla.header", "hours", config.getSlaHours())
                + "\n" + plugin.getLang().plain("sla.line",
                        "number", record.number,
                        "title", record.title,
                        "category", record.categoryName,
                        "age", ageHours)
                + "\n" + plugin.getLang().plain("sla.footer", "url", record.url),
                config.isSlaNotifyGroups(), config.isSlaNotifyPrivate());
    }

    /** 把「已处理」的结果播报到渠道（QQ 群 / Discord）。 */
    private void announceClosed(IssueRecord record) {
        broadcast(plugin.getLang().plain("tracking.announce-closed",
                "number", record.number,
                "title", record.title,
                "category", record.categoryName,
                "player", record.playerName,
                "url", record.url), true, true);
    }

    /**
     * 向所有已启用渠道推一条纯文本通知。
     *
     * @param groups   是否发到群 / 频道
     * @param privates 是否私信个人
     */
    private void broadcast(String message, boolean groups, boolean privates) {
        if (message == null || message.isEmpty()) {
            return;
        }
        for (Channel channel : plugin.getChannelManager().getChannels()) {
            try {
                channel.notifyAdmins(message, groups, privates);
            } catch (Throwable throwable) {
                plugin.getLogger().fine("通知经渠道 " + channel.getId() + " 发送失败："
                        + throwable.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 对外查询 / 修改
    // ------------------------------------------------------------------

    /** 全部跟踪记录（快照）。 */
    public List<IssueRecord> all() {
        return new ArrayList<IssueRecord>(records);
    }

    /** 按编号找一条记录，找不到返回 {@code null}。 */
    public IssueRecord find(int number) {
        for (IssueRecord record : records) {
            if (record.number == number) {
                return record;
            }
        }
        return null;
    }

    /** 管理员在游戏内关闭了 Issue：先把本地状态改掉，避免下一轮轮询又通知一遍。 */
    public void markClosedLocally(int number) {
        IssueRecord record = find(number);
        if (record == null) {
            return;
        }
        record.closed = true;
        record.closeNotified = true;
        record.closedAt = System.currentTimeMillis();
        save();
    }

    /** 玩家自己回复了 Issue：把已读评论数 +1，免得下一轮把他的回复当成新消息又推给他。 */
    public void markRepliedLocally(int number) {
        IssueRecord record = find(number);
        if (record == null) {
            return;
        }
        record.commentCount++;
        save();
    }

    /** 管理员在游戏内改了优先级：同步到本地记录，供 /iusse list 展示。 */
    public void markPriorityLocally(int number, String priorityName) {
        IssueRecord record = find(number);
        if (record == null) {
            return;
        }
        record.priorityName = priorityName == null ? "" : priorityName;
        save();
    }

    /** 管理员在游戏内关闭了 Issue：通知提交者，并按配置在渠道里播报。 */
    public void announceClosedLocally(int number) {
        IssueRecord record = find(number);
        if (record == null) {
            return;
        }
        notifyPlayer(record, plugin.getLang().prefixed("tracking.notify-closed",
                "title", record.title,
                "url", record.url));
        if (plugin.getPluginConfig().isTrackingAnnounceOnClose()) {
            announceClosed(record);
        }
    }

    // ------------------------------------------------------------------
    // 通知玩家
    // ------------------------------------------------------------------

    /**
     * 把一条消息推给指定玩家。
     * <p>
     * 顺序：玩家在线 → 游戏内消息；不在线 → 先试 QQ 私信（经 EasyBot 查绑定）；
     * 都不可用 → 排队等他上线补发。
     * <p>
     * 本方法可在异步线程调用（轮询、重发队列都在异步线程上跑）。
     */
    public void notifyPlayer(String playerUuid, String playerName, String message) {
        if (message == null || message.isEmpty()) {
            return;
        }
        UUID uuid = parseUuid(playerUuid);

        Player online = uuid == null ? null : Bukkit.getPlayer(uuid);
        if (online != null && online.isOnline()) {
            sendToPlayer(online, message);
            return;
        }

        if (plugin.getPluginConfig().isNotifyQqOffline() && sendByQq(playerName, message)) {
            return;
        }

        if (uuid != null && plugin.getPluginConfig().isTrackingQueueOffline()) {
            List<String> queue = pending.get(uuid);
            if (queue == null) {
                queue = new CopyOnWriteArrayList<String>();
                pending.put(uuid, queue);
            }
            queue.add(message);
        }
    }

    /** 把状态变化推给提交者。 */
    private void notifyPlayer(IssueRecord record, String message) {
        notifyPlayer(record.playerUuid, record.playerName, message);
    }

    private void sendToPlayer(final Player player, final String message) {
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    player.sendMessage(message);
                }
            }
        });
    }

    /** 经 EasyBot 查到玩家绑定的 QQ，再让 OneBot 私信他。 */
    private boolean sendByQq(String playerName, String message) {
        return sendByQq(playerName, message, true);
    }

    /**
     * 经 EasyBot 查到玩家绑定的 QQ，再让 OneBot 私信他。
     *
     * @param log 是否在成功时写日志（批量补发时为 false，避免刷屏）
     */
    private boolean sendByQq(String playerName, String message, boolean log) {
        if (playerName == null || playerName.isEmpty() || !EasyBotBridge.isAvailable()) {
            return false;
        }
        long qq = EasyBotBridge.queryQq(playerName);
        if (qq <= 0) {
            return false;
        }
        for (Channel channel : plugin.getChannelManager().getChannels()) {
            try {
                if (channel.sendPrivate(qq, message)) {
                    if (log) {
                        plugin.getLogger().info("已通过 QQ 私信通知 " + playerName + "（" + qq + "）");
                    }
                    return true;
                }
            } catch (Throwable throwable) {
                plugin.getLogger().fine("QQ 私信发送失败：" + throwable.getMessage());
            }
        }
        return false;
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 持久化
    // ------------------------------------------------------------------

    private synchronized void load() {
        if (file == null || !file.isFile()) {
            return;
        }
        try {
            String text = new String(Files.readAllBytes(file.toPath()), UTF_8).trim();
            if (text.isEmpty()) {
                return;
            }
            Object parsed = MiniJson.parse(text);
            if (!(parsed instanceof Map)) {
                return;
            }
            Object list = ((Map<?, ?>) parsed).get("issues");
            if (!(list instanceof List)) {
                return;
            }
            records.clear();
            for (Object element : (List<?>) list) {
                if (!(element instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                IssueRecord record = IssueRecord.fromMap((Map<String, Object>) element);
                if (record != null) {
                    records.add(record);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "读取 " + FILE_NAME + " 失败，本次忽略历史记录", e);
            records.clear();
        }
    }

    private synchronized void save() {
        if (file == null) {
            return;
        }
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                return;
            }
            Map<String, Object> root = new LinkedHashMap<String, Object>();
            List<Object> list = new ArrayList<Object>(records.size());
            for (IssueRecord record : records) {
                list.add(record.toMap());
            }
            root.put("issues", list);
            Files.write(file.toPath(), MiniJson.write(root).getBytes(UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存 " + FILE_NAME + " 失败", e);
        }
    }
}
