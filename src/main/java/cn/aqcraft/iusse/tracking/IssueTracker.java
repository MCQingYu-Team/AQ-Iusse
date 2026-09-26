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
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.net.Http;
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
                      String title, String categoryName) {
        if (number <= 0) {
            return;
        }
        for (IssueRecord existing : records) {
            if (existing.number == number) {
                return;
            }
        }
        records.add(new IssueRecord(number, url, playerName, playerUuid, title, categoryName,
                System.currentTimeMillis()));
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
        if (!config.isGitHubEnabled() || !config.hasToken() || !config.isRepoConfigured()) {
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

        Map<String, Object> json;
        try {
            Http.Response response = Http.get(issueUrl(config, record.number, ""),
                    headers(), config.getTimeoutMillis(), config.resolveProxy());
            if (!response.isSuccess()) {
                plugin.getLogger().fine("查询 Issue #" + record.number + " 失败：HTTP " + response.getCode());
                return false;
            }
            json = MiniJson.parseObject(response.getBody());
        } catch (Exception e) {
            plugin.getLogger().fine("查询 Issue #" + record.number + " 出错：" + e.getMessage());
            return false;
        }

        String state = MiniJson.string(json, "state");
        int comments = MiniJson.integer(json, "comments", 0);

        if ("closed".equalsIgnoreCase(state)) {
            if (record.closeNotified) {
                return false;
            }
            record.closed = true;
            record.closeNotified = true;
            notifyPlayer(record, plugin.getLang().prefixed("tracking.notify-closed",
                    "title", record.title,
                    "url", record.url));
            return true;
        }

        boolean changed = false;

        if (comments > record.commentCount) {
            record.commentCount = comments;
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

    /** 取 Issue 的最后一条评论，形如 {@code 用户名：内容}。 */
    private String fetchLastComment(int number) {
        PluginConfig config = plugin.getPluginConfig();
        try {
            Http.Response response = Http.get(
                    issueUrl(config, number, "/comments?per_page=100&sort=created&direction=desc"),
                    headers(), config.getTimeoutMillis(), config.resolveProxy());
            if (!response.isSuccess()) {
                return null;
            }
            Object parsed = MiniJson.parse(response.getBody());
            if (!(parsed instanceof List)) {
                return null;
            }
            List<?> list = (List<?>) parsed;
            if (list.isEmpty()) {
                return null;
            }
            Object last = list.get(list.size() - 1);
            if (!(last instanceof Map)) {
                return null;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> map = (Map<String, Object>) last;
            String body = MiniJson.string(map, "body");
            String login = null;
            Object user = map.get("user");
            if (user instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> userMap = (Map<String, Object>) user;
                login = MiniJson.string(userMap, "login");
            }
            if (body == null) {
                return null;
            }
            return login == null || login.isEmpty() ? body : login + "：" + body;
        } catch (Exception e) {
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
        String message = plugin.getLang().text("sla.header", "hours", config.getSlaHours())
                + "\n" + plugin.getLang().text("sla.line",
                        "number", record.number,
                        "title", record.title,
                        "category", record.categoryName,
                        "age", ageHours)
                + "\n" + plugin.getLang().text("sla.footer", "url", record.url);

        for (Channel channel : plugin.getChannelManager().getChannels()) {
            try {
                channel.notifyAdmins(message);
            } catch (Throwable throwable) {
                plugin.getLogger().fine("SLA 提醒经渠道 " + channel.getId() + " 发送失败："
                        + throwable.getMessage());
            }
        }
    }

    // ------------------------------------------------------------------
    // 通知玩家
    // ------------------------------------------------------------------

    private void notifyPlayer(IssueRecord record, String message) {
        final UUID uuid = parseUuid(record.playerUuid);
        if (uuid == null || message == null || message.isEmpty()) {
            return;
        }
        final boolean queueOffline = plugin.getPluginConfig().isTrackingQueueOffline();

        // 轮询跑在异步线程，发送消息切回主线程
        Bukkit.getScheduler().runTask(plugin, new Runnable() {
            @Override
            public void run() {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null && player.isOnline()) {
                    player.sendMessage(message);
                    return;
                }
                if (queueOffline) {
                    List<String> queue = pending.get(uuid);
                    if (queue == null) {
                        queue = new CopyOnWriteArrayList<String>();
                        pending.put(uuid, queue);
                    }
                    queue.add(message);
                }
            }
        });
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
    // HTTP 辅助
    // ------------------------------------------------------------------

    private String issueUrl(PluginConfig config, int number, String suffix) {
        return config.getApiBase() + "/repos/" + config.getOwner() + "/" + config.getRepo()
                + "/issues/" + number + suffix;
    }

    private Map<String, String> headers() {
        PluginConfig config = plugin.getPluginConfig();
        Map<String, String> headers = new LinkedHashMap<String, String>();
        headers.put("Authorization", "Bearer " + config.getToken().trim());
        headers.put("Accept", "application/vnd.github+json");
        headers.put("X-GitHub-Api-Version", "2022-11-28");
        headers.put("User-Agent", "AQIssue/" + plugin.getPluginMeta().getVersion());
        return headers;
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
