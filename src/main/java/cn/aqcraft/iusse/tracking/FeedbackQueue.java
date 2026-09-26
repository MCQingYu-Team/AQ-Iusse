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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.channel.ChannelResult;
import cn.aqcraft.iusse.channel.Submission;
import cn.aqcraft.iusse.config.Category;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.MiniJson;

/**
 * 投递失败重发队列。
 * <p>
 * 之前所有渠道都失败时，玩家会看到「投递失败」，然后这条反馈就永久消失了 ——
 * 而失败的原因往往是服务器到 GitHub 的网络抖了一下（国内很常见），
 * 或者是反代 / 代理临时不可用，过几分钟自己就好了。
 * <p>
 * 于是失败的反馈会存盘到 {@code plugins/AQIssue/queue.json}，之后每隔一段时间重试；
 * 成功时补发 GitHub Issue 并通知玩家「已补发成功」，超过重试上限则告知失败收场。
 */
public class FeedbackQueue {

    private static final Charset UTF_8 = Charset.forName("UTF-8");
    private static final String FILE_NAME = "queue.json";
    private static final long MINUTE_TICKS = 1200L;

    /** 队列容量上限，避免磁盘被无限写大。 */
    private static final int MAX_ITEMS = 200;
    /** 单轮最多重试几条，免得一次把所有额度打光、也避免单轮耗时过长。 */
    private static final int MAX_PER_RUN = 5;

    private final AqIssuePlugin plugin;
    private final List<QueuedFeedback> items = new CopyOnWriteArrayList<QueuedFeedback>();

    private File file;
    private int taskId = -1;
    /** 防止上一轮还没跑完又进来一轮。 */
    private volatile boolean busy;

    public FeedbackQueue(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

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
        if (!config.isRetryQueueEnabled()) {
            return;
        }
        long interval = Math.max(1, config.getRetryIntervalMinutes()) * MINUTE_TICKS;
        this.taskId = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                flush();
            }
        }, interval, interval).getTaskId();
        plugin.getLogger().info("反馈重发队列已启动：每 " + config.getRetryIntervalMinutes()
                + " 分钟重试一次（当前积压 " + items.size() + " 条）");
    }

    private void cancelTask() {
        if (taskId != -1) {
            Bukkit.getScheduler().cancelTask(taskId);
            taskId = -1;
        }
    }

    // ------------------------------------------------------------------
    // 入队与查询
    // ------------------------------------------------------------------

    /**
     * 把一条投递失败的反馈存起来等待重发。
     *
     * @return true 表示已入队；队列功能关闭或已满时返回 false
     */
    public boolean enqueue(String playerUuid, String playerName, String categoryId, String categoryName,
                           String title, String body) {
        if (!plugin.getPluginConfig().isRetryQueueEnabled()) {
            return false;
        }
        if (items.size() >= MAX_ITEMS) {
            plugin.getLogger().warning("重发队列已满（" + MAX_ITEMS + " 条），丢弃一条来自 "
                    + playerName + " 的反馈：" + title);
            return false;
        }
        items.add(new QueuedFeedback(UUID.randomUUID().toString(), playerUuid, playerName,
                categoryId, categoryName, title, body, System.currentTimeMillis()));
        save();
        plugin.getLogger().info("反馈投递失败，已存入重发队列：" + playerName + " / " + title
                + "（队列共 " + items.size() + " 条）");
        return true;
    }

    /** 队列中积压的条数。 */
    public int size() {
        return items.size();
    }

    /** 队列快照（用于 /iusse stats）。 */
    public List<QueuedFeedback> snapshot() {
        return new ArrayList<QueuedFeedback>(items);
    }

    // ------------------------------------------------------------------
    // 重试
    // ------------------------------------------------------------------

    /** 在异步线程上依次重试队列里的条目。 */
    private void flush() {
        PluginConfig config = plugin.getPluginConfig();
        if (!config.isRetryQueueEnabled() || items.isEmpty()) {
            return;
        }
        if (busy) {
            return;
        }
        busy = true;
        try {
            long now = System.currentTimeMillis();
            long keepMillis = Math.max(1, config.getRetryKeepHours()) * 3600000L;
            int processed = 0;

            for (QueuedFeedback item : new ArrayList<QueuedFeedback>(items)) {
                if (processed >= MAX_PER_RUN) {
                    break;
                }

                // 超期或重试次数用尽 -> 放弃并告知玩家
                if (item.attempts >= config.getRetryMaxAttempts() || now - item.createdAt > keepMillis) {
                    items.remove(item);
                    notifyLater(item, plugin.getLang().prefixed("tracking.notify-queued-dropped",
                            "title", item.title, "attempts", item.attempts));
                    plugin.getLogger().warning("反馈重发失败已放弃：" + item.playerName + " / " + item.title
                            + "（已尝试 " + item.attempts + " 次）");
                    continue;
                }

                Category category = config.findCategory(item.categoryId);
                if (category == null) {
                    // 分类被管理员删掉了，没法重建 Submission
                    items.remove(item);
                    plugin.getLogger().warning("反馈重发时分类已不存在，已丢弃：" + item.categoryId);
                    continue;
                }

                processed++;
                item.attempts++;

                Submission submission = plugin.buildSubmission(item.playerName, item.playerUuid,
                        category, item.title, item.body);
                List<ChannelResult> results = plugin.getChannelManager().submitAllSync(submission);

                boolean anySuccess = false;
                for (ChannelResult result : results) {
                    if (result.isSuccess()) {
                        anySuccess = true;
                        break;
                    }
                }

                if (anySuccess) {
                    items.remove(item);
                    plugin.trackIssue(submission, results);
                    String link = submission.getPrimaryLink();
                    notifyLater(item, plugin.getLang().prefixed("tracking.notify-queued-success",
                            "title", item.title, "url", link == null ? "" : link));
                    plugin.getLogger().info("反馈已补发成功：" + item.playerName + " / " + item.title);
                }
            }
            save();
        } catch (Throwable throwable) {
            plugin.logError("重发队列处理失败", throwable);
        } finally {
            busy = false;
        }
    }

    /** 通知当初提交的玩家（在线发游戏内，离线走 QQ 或排队等上线）。 */
    private void notifyLater(QueuedFeedback item, String message) {
        try {
            plugin.getIssueTracker().notifyPlayer(item.playerUuid, item.playerName, message);
        } catch (Throwable throwable) {
            plugin.getLogger().fine("重发结果通知玩家失败：" + throwable.getMessage());
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
            Object list = ((Map<?, ?>) parsed).get("queue");
            if (!(list instanceof List)) {
                return;
            }
            items.clear();
            for (Object element : (List<?>) list) {
                if (!(element instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                QueuedFeedback item = QueuedFeedback.fromMap((Map<String, Object>) element);
                if (item != null) {
                    items.add(item);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "读取 " + FILE_NAME + " 失败，本次忽略重发队列", e);
            items.clear();
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
            List<Object> list = new ArrayList<Object>(items.size());
            for (QueuedFeedback item : items) {
                list.add(item.toMap());
            }
            root.put("queue", list);
            Files.write(file.toPath(), MiniJson.write(root).getBytes(UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "保存 " + FILE_NAME + " 失败", e);
        }
    }
}
