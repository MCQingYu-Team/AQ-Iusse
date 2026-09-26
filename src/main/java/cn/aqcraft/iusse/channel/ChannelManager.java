package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Level;

import org.bukkit.Bukkit;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.net.Http;

/**
 * 通道编排：持有当前可用的投递渠道，负责生命周期与异步分发。
 */
public class ChannelManager {

    private final AqIssuePlugin plugin;
    private final List<Channel> channels = new ArrayList<Channel>();

    public ChannelManager(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    /** 按当前配置重建全部通道（reload 时调用）。 */
    public void reload() {
        stopAll();

        PluginConfig config = plugin.getPluginConfig();
        String userAgent = "AQIssue/" + plugin.getPluginMeta().getVersion();

        List<Channel> candidates = new ArrayList<Channel>();
        candidates.add(new GitHubChannel(config, userAgent));
        candidates.add(new DiscordChannel(config));
        candidates.add(new OneBotChannel(plugin, config));

        for (Channel channel : candidates) {
            if (!channel.isEnabled()) {
                plugin.getLogger().info("渠道 " + channel.getId() + " 未启用：" + channel.getDisabledReason());
                continue;
            }
            try {
                channel.start();
                channels.add(channel);
                plugin.getLogger().info("渠道 " + channel.getId() + " 已就绪");
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "渠道 " + channel.getId() + " 启动失败，已跳过", e);
            }
        }

        if (channels.isEmpty()) {
            plugin.getLogger().warning("当前没有任何可用的投递渠道，玩家提交会全部失败。");
        }
    }

    /** 关闭并清空所有通道。 */
    public void stopAll() {
        for (Channel channel : channels) {
            try {
                channel.stop();
            } catch (Exception e) {
                plugin.getLogger().log(Level.FINE, "关闭渠道 " + channel.getId() + " 时出错", e);
            }
        }
        channels.clear();
    }

    public List<Channel> getChannels() {
        return Collections.unmodifiableList(channels);
    }

    public boolean isEmpty() {
        return channels.isEmpty();
    }

    /**
     * 在异步线程里依次投递到所有通道，全部结束后回主线程回调。
     *
     * @param callback 主线程回调，参数是每个通道的结果
     */
    public void submitAll(final Submission submission, final Consumer<List<ChannelResult>> callback) {
        final List<Channel> targets = new ArrayList<Channel>(channels);
        Bukkit.getScheduler().runTaskAsynchronously(plugin, new Runnable() {
            @Override
            public void run() {
                final List<ChannelResult> results = new ArrayList<ChannelResult>(targets.size());
                for (Channel channel : targets) {
                    try {
                        results.add(ChannelResult.success(channel, channel.submit(submission)));
                    } catch (Throwable throwable) {
                        results.add(ChannelResult.failure(channel, describe(throwable)));
                        plugin.getLogger().warning("渠道 " + channel.getId() + " 投递失败："
                                + throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
                    }
                }
                Bukkit.getScheduler().runTask(plugin, new Runnable() {
                    @Override
                    public void run() {
                        callback.accept(results);
                    }
                });
            }
        });
    }

    private static String describe(Throwable throwable) {
        if (throwable instanceof IOException) {
            String message = throwable.getMessage();
            if (message != null && !message.isEmpty()) {
                return message;
            }
            return Http.describe((IOException) throwable);
        }
        String message = throwable.getMessage();
        return message == null || message.isEmpty() ? throwable.getClass().getSimpleName() : message;
    }
}
