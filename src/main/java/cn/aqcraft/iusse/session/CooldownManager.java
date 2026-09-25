package cn.aqcraft.iusse.session;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * 提交冷却管理（纯内存，重启即失效）。
 */
public class CooldownManager {

    private final Map<UUID, Long> cooldowns = new HashMap<UUID, Long>();

    /** 记录一次成功提交，开始计算冷却。 */
    public void markSubmitted(UUID playerId) {
        cooldowns.put(playerId, System.currentTimeMillis());
    }

    /**
     * 剩余冷却秒数。
     *
     * @return 0 表示可以立即提交
     */
    public long remaining(UUID playerId, int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            return 0L;
        }
        Long last = cooldowns.get(playerId);
        if (last == null) {
            return 0L;
        }
        long remain = cooldownSeconds * 1000L - (System.currentTimeMillis() - last);
        if (remain <= 0) {
            cooldowns.remove(playerId);
            return 0L;
        }
        return remain / 1000L + 1L;
    }

    /** 清理已过期的记录，避免长时间运行后无限增长。 */
    public void purge(int cooldownSeconds) {
        if (cooldownSeconds <= 0) {
            clear();
            return;
        }
        long expireBefore = System.currentTimeMillis() - cooldownSeconds * 1000L;
        Iterator<Map.Entry<UUID, Long>> iterator = cooldowns.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue() < expireBefore) {
                iterator.remove();
            }
        }
    }

    public void clear() {
        cooldowns.clear();
    }
}
