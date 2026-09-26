package cn.aqcraft.iusse.listener;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

import cn.aqcraft.iusse.AqIssuePlugin;

/**
 * 玩家上线后，补发他离线期间攒下的反馈状态通知。
 */
public class PlayerJoinListener implements Listener {

    /** 延迟 3 秒发送，避开进服瞬间的其它提示。 */
    private static final long DELAY_TICKS = 60L;

    private final AqIssuePlugin plugin;

    public PlayerJoinListener(AqIssuePlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        plugin.getServer().getScheduler().runTaskLater(plugin, new Runnable() {
            @Override
            public void run() {
                if (player.isOnline()) {
                    plugin.getIssueTracker().flushPending(player);
                }
            }
        }, DELAY_TICKS);
    }
}
