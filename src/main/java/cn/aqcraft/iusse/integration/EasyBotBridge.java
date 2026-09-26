package cn.aqcraft.iusse.integration;

import java.lang.reflect.Method;
import java.util.List;

/**
 * EasyBot 联动：查询 MC 玩家绑定的 QQ 号。
 * <p>
 * 走的是 EasyBot 的 Bridge 扩展接口：
 *
 * <pre>
 * BridgeClient.getInstance().queryBindStatus(playerName)  →  QueryBindStatusResultPacket
 *     └─ socialAccounts: [{ platform: "qq", name: "昵称", uuid: "164907681" }, ...]
 * </pre>
 *
 * 全部用反射调用，理由有两点：
 * <ul>
 *   <li>easybot-bridge 只发布在 GitHub Packages，引入它需要额外的 Token 才能拉取依赖；</li>
 *   <li>没装 EasyBot 的服务器上不应该因为缺少这个类而报错。</li>
 * </ul>
 * 因此这里只在首次调用时解析一次方法句柄，失败后永久降级（返回 0）。
 */
public final class EasyBotBridge {

    private static final String CLIENT_CLASS = "com.springwater.easybot.bridge.BridgeClient";
    private static final String RESULT_CLASS = "com.springwater.easybot.bridge.packet.QueryBindStatusResultPacket";
    private static final String ACCOUNT_CLASS = "com.springwater.easybot.bridge.packet.BindStatusAccount";

    private static boolean resolved;
    private static boolean available;
    private static Method getInstance;
    private static Method queryBindStatus;
    private static Method getSocialAccounts;
    private static Method getPlatform;
    private static Method getUuid;

    private EasyBotBridge() {
    }

    /** EasyBot 是否可用（装了 EasyBot 且 Bridge 类能被加载）。 */
    public static boolean isAvailable() {
        resolve();
        return available;
    }

    private static synchronized void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;
        try {
            Class<?> clientClass = Class.forName(CLIENT_CLASS);
            Class<?> resultClass = Class.forName(RESULT_CLASS);
            Class<?> accountClass = Class.forName(ACCOUNT_CLASS);

            getInstance = clientClass.getMethod("getInstance");
            queryBindStatus = clientClass.getMethod("queryBindStatus", String.class);
            getSocialAccounts = resultClass.getMethod("getSocialAccounts");
            getPlatform = accountClass.getMethod("getPlatform");
            getUuid = accountClass.getMethod("getUuid");

            available = true;
        } catch (Throwable throwable) {
            available = false;
        }
    }

    /**
     * 查询玩家绑定的 QQ 号。
     * <p>
     * 这个调用会阻塞等待 Bridge 回调（EasyBot 内部 5 秒超时），
     * <b>必须在异步线程里调用</b>。
     *
     * @return QQ 号；未绑定、EasyBot 未连接或查询失败时返回 0
     */
    public static long queryQq(String playerName) {
        if (playerName == null || playerName.isEmpty() || !isAvailable()) {
            return 0L;
        }
        try {
            Object client = getInstance.invoke(null);
            if (client == null) {
                return 0L;
            }
            Object result = queryBindStatus.invoke(client, playerName);
            if (result == null) {
                return 0L;
            }
            Object accounts = getSocialAccounts.invoke(result);
            if (!(accounts instanceof List)) {
                return 0L;
            }
            for (Object account : (List<?>) accounts) {
                if (account == null) {
                    continue;
                }
                if (!"qq".equalsIgnoreCase(String.valueOf(getPlatform.invoke(account)))) {
                    continue;
                }
                Object uuid = getUuid.invoke(account);
                String raw = uuid == null ? "" : String.valueOf(uuid).trim();
                if (raw.isEmpty()) {
                    continue;
                }
                try {
                    return Long.parseLong(raw);
                } catch (NumberFormatException ignored) {
                    // QQ 号不是纯数字，跳过
                }
            }
        } catch (Throwable ignored) {
            // EasyBot 未连接、RPC 超时、玩家未绑定等都会走到这里，静默降级
        }
        return 0L;
    }
}
