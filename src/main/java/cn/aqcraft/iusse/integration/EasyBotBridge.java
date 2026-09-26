package cn.aqcraft.iusse.integration;

import java.lang.reflect.Method;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

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
 *
 * <h2>为什么不用「探测一次就定死」</h2>
 * 早先的写法是首次探测失败就永久降级，于是所有问题都表现为同一句话
 * 「未安装 EasyBot」—— 哪怕服务器上装得好好的。真实的失败原因至少有三类：
 * <ul>
 *   <li><b>类加载器看不到</b>：Bukkit 的插件类加载器只在 {@code softdepend} 声明后才保证顺序，
 *       EasyBot 若比本插件晚加载，那一刻确实找不到类</li>
 *   <li><b>版本过旧</b>：没有 {@code QUERY_BIND_STATUS} 这套接口</li>
 *   <li><b>Bridge 没连上</b>：类都在，但 {@code getInstance()} 返回 null（EasyBot 还没连上主程序）</li>
 * </ul>
 * 现在改成：失败也只算「本次失败」，一分钟后再试；同时把原因记下来，
 * 通过 {@link #getFailureReason()} 暴露给 {@code /iusse test} 显示。
 */
public final class EasyBotBridge {

    private static final String PLUGIN_NAME = "EasyBot";
    private static final String CLIENT_CLASS = "com.springwater.easybot.bridge.BridgeClient";
    private static final String RESULT_CLASS = "com.springwater.easybot.bridge.packet.QueryBindStatusResultPacket";
    private static final String ACCOUNT_CLASS = "com.springwater.easybot.bridge.packet.BindStatusAccount";
    private static final String BIND_INFO_CLASS = "com.springwater.easybot.bridge.packet.GetBindInfoResultPacket";

    /** 探测失败后隔多久再试一次。 */
    private static final long RETRY_INTERVAL_MILLIS = 60000L;

    private static boolean resolved;
    private static boolean available;
    private static String failure = "尚未探测过";
    private static long nextAttemptAt;

    private static ClassLoader loader;
    private static Method getInstance;
    private static Method queryBindStatus;
    private static Method getSocialAccounts;
    private static Method getPlatform;
    private static Method getUuid;

    /** 兜底通道：老版本没有 QUERY_BIND_STATUS 时退回 GET_BIND_INFO。 */
    private static boolean bindInfoResolved;
    private static Method getBindInfo;
    private static Method getBindInfoPlatform;
    private static Method getBindInfoId;

    private EasyBotBridge() {
    }

    /**
     * EasyBot 是否可用（装了 EasyBot 且 Bridge 类能被加载）。
     * <p>
     * 失败不会永久缓存：一分钟后会自动重试，因此 EasyBot 后加载 / 中途重载都能自愈。
     */
    public static boolean isAvailable() {
        if (available) {
            return true;
        }
        resolve();
        return available;
    }

    /**
     * Bridge 是否已连上 EasyBot 主程序。
     * <p>
     * 与 {@link #isAvailable()} 的区别：类在不在 vs 连接通没通。
     * 两者都不满足时返回 0，但给玩家/管理员的提示不一样。
     */
    public static boolean isReady() {
        if (!isAvailable() || getInstance == null) {
            return false;
        }
        try {
            return getInstance.invoke(null) != null;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 上次探测失败的原因，供 {@code /iusse test} 展示；可用时为空串。 */
    public static String getFailureReason() {
        return failure == null ? "" : failure;
    }

    /** 一句话描述当前状态，用于启动日志与自检。 */
    public static String describeState() {
        if (isAvailable()) {
            return isReady() ? "已就绪" : "已加载，但还没连上 EasyBot 主程序";
        }
        return "未检测到（" + getFailureReason() + "）";
    }

    /**
     * EasyBot 在插件管理器里的状态。
     * <p>
     * 用来区分两种长得很像的情况：EasyBot 根本没加载起来（插件管理器里查不到 / 未启用），
     * 还是加载了但本插件看不到它的类（类加载器问题）。
     */
    public static String describePluginPresence() {
        try {
            Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (plugin == null) {
                return "插件管理器里没有 " + PLUGIN_NAME;
            }
            return PLUGIN_NAME + " " + plugin.getPluginMeta().getVersion()
                    + (plugin.isEnabled() ? "（已启用）" : "（已加载但未启用）");
        } catch (Throwable throwable) {
            return "无法查询 " + PLUGIN_NAME + " 插件状态：" + throwable.getClass().getSimpleName();
        }
    }

    private static synchronized void resolve() {
        if (available) {
            return;
        }
        long now = System.currentTimeMillis();
        if (resolved && now < nextAttemptAt) {
            return;
        }
        resolved = true;
        nextAttemptAt = now + RETRY_INTERVAL_MILLIS;

        // 每次重新探测前先清空旧句柄
        loader = null;
        getInstance = null;
        queryBindStatus = null;
        getSocialAccounts = null;
        getPlatform = null;
        getUuid = null;
        bindInfoResolved = false;
        getBindInfo = null;
        getBindInfoPlatform = null;
        getBindInfoId = null;
        available = false;

        try {
            ClassLoader found = pickClassLoader();
            if (found == null) {
                failure = "找不到能加载 " + CLIENT_CLASS + " 的类加载器 —— 请确认服务端装的确实是 "
                        + PLUGIN_NAME + "（需要 2.1.2 及以上版本）";
                return;
            }

            // initialize=false：只取句柄，不去触发 EasyBot 的静态初始化，避免探测本身产生副作用
            Class<?> clientClass = Class.forName(CLIENT_CLASS, false, found);
            Class<?> resultClass = Class.forName(RESULT_CLASS, false, found);
            Class<?> accountClass = Class.forName(ACCOUNT_CLASS, false, found);

            getInstance = clientClass.getMethod("getInstance");
            queryBindStatus = clientClass.getMethod("queryBindStatus", String.class);
            getSocialAccounts = resultClass.getMethod("getSocialAccounts");
            getPlatform = accountClass.getMethod("getPlatform");
            getUuid = accountClass.getMethod("getUuid");

            loader = found;
            available = true;
            failure = "";
        } catch (Throwable throwable) {
            available = false;
            failure = describe(throwable);
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
        long qq = queryByBindStatus(playerName);
        if (qq > 0) {
            return qq;
        }
        // 老版本 EasyBot 没有 QUERY_BIND_STATUS 时可以退回 GET_BIND_INFO
        return queryByBindInfo(playerName);
    }

    /** 主通道：QUERY_BIND_STATUS → socialAccounts[platform=qq].uuid。 */
    private static long queryByBindStatus(String playerName) {
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
                long parsed = parseQq(String.valueOf(getUuid.invoke(account)));
                if (parsed > 0) {
                    return parsed;
                }
            }
        } catch (Throwable ignored) {
            // EasyBot 未连接、RPC 超时、玩家未绑定等都会走到这里，静默降级
        }
        return 0L;
    }

    /** 兜底通道：GET_BIND_INFO → id（platform 不是 qq 时忽略）。 */
    private static long queryByBindInfo(String playerName) {
        if (!resolveBindInfo()) {
            return 0L;
        }
        try {
            Object client = getInstance.invoke(null);
            if (client == null) {
                return 0L;
            }
            Object result = getBindInfo.invoke(client, playerName);
            if (result == null) {
                return 0L;
            }
            Object platform = getBindInfoPlatform.invoke(result);
            if (platform != null && !"qq".equalsIgnoreCase(String.valueOf(platform))) {
                return 0L;
            }
            return parseQq(String.valueOf(getBindInfoId.invoke(result)));
        } catch (Throwable ignored) {
            return 0L;
        }
    }

    /** 延迟解析兜底通道的句柄，失败一次就记住不再试。 */
    private static synchronized boolean resolveBindInfo() {
        if (bindInfoResolved) {
            return getBindInfo != null;
        }
        bindInfoResolved = true;
        try {
            ClassLoader target = loader != null ? loader : pickClassLoader();
            if (target == null) {
                return false;
            }
            Class<?> clientClass = Class.forName(CLIENT_CLASS, false, target);
            Class<?> infoClass = Class.forName(BIND_INFO_CLASS, false, target);
            getBindInfo = clientClass.getMethod("getBindInfo", String.class);
            getBindInfoPlatform = infoClass.getMethod("getPlatform");
            getBindInfoId = infoClass.getMethod("getId");
            return true;
        } catch (Throwable throwable) {
            getBindInfo = null;
            return false;
        }
    }

    // ------------------------------------------------------------------
    // 类加载与错误描述
    // ------------------------------------------------------------------

    /**
     * 找一个能看见 EasyBot 类的类加载器。
     * <p>
     * 先用自己的（Bukkit 的插件类加载器会跨插件查找，但依赖加载顺序），
     * 拿不到就直接问 EasyBot 插件实例的类加载器，那是最可靠的。
     */
    private static ClassLoader pickClassLoader() {
        ClassLoader own = EasyBotBridge.class.getClassLoader();
        if (canLoad(own)) {
            return own;
        }
        try {
            Plugin easybot = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
            if (easybot != null) {
                ClassLoader other = easybot.getClass().getClassLoader();
                if (canLoad(other)) {
                    return other;
                }
            }
        } catch (Throwable ignored) {
            // 插件管理器还没就绪，走后面的兜底
        }
        ClassLoader system = ClassLoader.getSystemClassLoader();
        return canLoad(system) ? system : null;
    }

    private static boolean canLoad(ClassLoader candidate) {
        if (candidate == null) {
            return false;
        }
        try {
            Class.forName(CLIENT_CLASS, false, candidate);
            return true;
        } catch (Throwable throwable) {
            return false;
        }
    }

    /** 把各种 Throwable 翻译成「该怎么办」。 */
    private static String describe(Throwable throwable) {
        if (throwable instanceof ClassNotFoundException) {
            return "找不到 " + throwable.getMessage() + "，EasyBot 需要 2.1.2 及以上版本";
        }
        if (throwable instanceof NoClassDefFoundError) {
            return "缺少依赖类 " + throwable.getMessage() + "，EasyBot 可能未完整加载";
        }
        if (throwable instanceof NoSuchMethodException) {
            return "Bridge 接口与预期不符（" + throwable.getMessage() + "），EasyBot 版本可能过旧";
        }
        if (throwable instanceof ExceptionInInitializerError) {
            return "初始化 EasyBot Bridge 失败：" + throwable.getCause();
        }
        String message = throwable.getMessage();
        return throwable.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }

    /**
     * 从任意平台标识里抽出 QQ 号。
     * <p>
     * 有些平台把 uuid 写成带前缀的形式，这里只取其中的连续数字，
     * 并要求长度在 5~12 位之间（QQ 号的实际范围）。
     */
    private static long parseQq(String raw) {
        if (raw == null || raw.isEmpty() || "null".equals(raw)) {
            return 0L;
        }
        StringBuilder digits = new StringBuilder(raw.length());
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (c >= '0' && c <= '9') {
                digits.append(c);
            }
        }
        if (digits.length() < 5 || digits.length() > 12) {
            return 0L;
        }
        try {
            return Long.parseLong(digits.toString());
        } catch (NumberFormatException e) {
            return 0L;
        }
    }
}
