package cn.aqcraft.iusse.integration;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * EasyBot 联动：查询 MC 玩家绑定的 QQ 号。
 * <p>
 * 全部用反射调用，理由有两点：
 * <ul>
 *   <li>easybot-bridge 只发布在 GitHub Packages，引入它需要额外的 Token 才能拉取依赖；</li>
 *   <li>没装 EasyBot 的服务器上不应该因为缺少这个类而报错。</li>
 * </ul>
 *
 * <h2>Bridge 接口是随版本变的，所以不能「一荣俱荣」</h2>
 * 实测：EasyBot 2.3.1 的 Bridge 里有 {@code BridgeClient}，但<b>没有</b>
 * {@code QueryBindStatusResultPacket}（那是 bridge 1.5 才加的）。
 * 早先的写法把五个类、五个方法一次全要齐，只要有一个缺失就整体报废，
 * 对外表现成「未安装 EasyBot」—— 排查半天其实只是版本差异。
 * <p>
 * 现在的结构：<b>只有 {@code BridgeClient} 与它的静态 {@code getInstance()} 是必需的</b>，
 * 查询接口做成三套独立方案，按优先级尝试，能解析出哪套就用哪套：
 * <ol>
 *   <li>{@code QUERY_BIND_STATUS} —— 一次拿到所有平台（bridge 1.5+）</li>
 *   <li>{@code GET_SOCIAL_ACCOUNT} —— 单个平台账号（旧版即有）</li>
 *   <li>{@code GET_BIND_INFO} —— 单个绑定信息（旧版即有）</li>
 * </ol>
 * 另外：探测失败不会永久缓存（一分钟后自动重试），且失败原因会记录下来，
 * 通过 {@link #getFailureReason()} 给 {@code /iusse test} 显示。
 */
public final class EasyBotBridge {

    private static final String PLUGIN_NAME = "EasyBot";
    private static final String CLIENT_CLASS = "com.springwater.easybot.bridge.BridgeClient";

    /** Bridge 1.5 起新增的「查绑定状态」，能一次拿到所有平台。 */
    private static final String BIND_STATUS_RESULT =
            "com.springwater.easybot.bridge.packet.QueryBindStatusResultPacket";
    private static final String BIND_STATUS_ACCOUNT =
            "com.springwater.easybot.bridge.packet.BindStatusAccount";

    /** 更早就存在的两条接口，作为旧版 EasyBot 的兜底。 */
    private static final String SOCIAL_ACCOUNT_RESULT =
            "com.springwater.easybot.bridge.packet.GetSocialAccountResultPacket";
    private static final String BIND_INFO_RESULT =
            "com.springwater.easybot.bridge.packet.GetBindInfoResultPacket";

    /** 探测失败后隔多久再试一次。 */
    private static final long RETRY_INTERVAL_MILLIS = 60000L;

    private static boolean resolved;
    private static boolean available;
    private static String failure = "尚未探测过";
    private static long nextAttemptAt;

    private static ClassLoader loader;
    /** 唯一必需的入口：BridgeClient 类本身 + 它的静态 getInstance()。 */
    private static Method getInstance;
    /** 可选的查询方案，按优先级排列；每套独立解析，能解析出哪套就用哪套。 */
    private static final List<QueryPlan> plans = new ArrayList<QueryPlan>();

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
        if (!isAvailable()) {
            return "未检测到（" + getFailureReason() + "）";
        }
        if (!isReady()) {
            return "已加载，但还没连上 EasyBot 主程序";
        }
        QueryPlan plan = activePlan();
        if (plan == null) {
            return "已就绪，但查不到绑定 —— " + queryFailureSummary();
        }
        return "已就绪（查询方式 " + plan.label + "）";
    }

    /** 当前会使用的查询方案名，都用不了时返回空串。 */
    public static String getQueryStrategy() {
        isAvailable();
        QueryPlan plan = activePlan();
        return plan == null ? "" : plan.label;
    }

    /** 三套查询方案都不可用时的原因汇总。 */
    public static String queryFailureSummary() {
        isAvailable();
        StringBuilder builder = new StringBuilder();
        for (QueryPlan plan : plans) {
            if (plan.isUsable()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append("；");
            }
            builder.append(plan.label).append("：").append(plan.unavailable);
        }
        return builder.length() == 0 ? "没有可用的查询接口" : builder.toString();
    }

    private static QueryPlan activePlan() {
        for (QueryPlan plan : plans) {
            if (plan.isUsable()) {
                return plan;
            }
        }
        return null;
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
        plans.clear();
        available = false;

        try {
            ClassLoader found = pickClassLoader();
            if (found == null) {
                failure = "找不到能加载 " + CLIENT_CLASS + " 的类加载器 —— 请确认服务端装的确实是 " + PLUGIN_NAME;
                return;
            }

            // initialize=false：只取句柄，不去触发 EasyBot 的静态初始化，避免探测本身产生副作用
            Class<?> clientClass = Class.forName(CLIENT_CLASS, false, found);
            getInstance = clientClass.getMethod("getInstance");

            // 三套查询方案：新版优先，旧版会自动落到后面两套上
            plans.add(buildPlan(found, clientClass, "QUERY_BIND_STATUS", "queryBindStatus",
                    BIND_STATUS_RESULT, BIND_STATUS_ACCOUNT, "getSocialAccounts", "getPlatform", "getUuid"));
            plans.add(buildPlan(found, clientClass, "GET_SOCIAL_ACCOUNT", "getSocialAccount",
                    SOCIAL_ACCOUNT_RESULT, null, null, "getPlatform", "getUuid"));
            plans.add(buildPlan(found, clientClass, "GET_BIND_INFO", "getBindInfo",
                    BIND_INFO_RESULT, null, null, "getPlatform", "getId"));

            loader = found;
            available = true;
            failure = "";
        } catch (Throwable throwable) {
            available = false;
            failure = describe(throwable);
        }
    }

    /**
     * 解析一套查询方案。
     * <p>
     * 任何一步失败都只让这一套不可用，不影响其它方案 —— EasyBot 各版本的 Bridge 接口不一致，
     * 「一套不通用就全部报废」正是之前误报「未安装」的根源。
     *
     * @param resultClassName  结果类名
     * @param accountClassName 账号元素类名；{@code null} 表示结果对象本身就是账号
     * @param listAccessorName 从结果对象取账号列表的方法名；{@code null} 表示结果是单个账号
     */
    private static QueryPlan buildPlan(ClassLoader loader, Class<?> clientClass, String label,
                                       String clientMethodName, String resultClassName,
                                       String accountClassName, String listAccessorName,
                                       String platformGetterName, String idGetterName) {
        QueryPlan plan = new QueryPlan(label);
        try {
            plan.entry = clientClass.getMethod(clientMethodName, String.class);

            Class<?> resultClass = Class.forName(resultClassName, false, loader);
            Class<?> accountClass = accountClassName == null
                    ? resultClass : Class.forName(accountClassName, false, loader);

            if (listAccessorName != null) {
                plan.listAccessor = resultClass.getMethod(listAccessorName);
                if (!List.class.isAssignableFrom(plan.listAccessor.getReturnType())) {
                    throw new NoSuchMethodException(listAccessorName + " 的返回类型不是 List");
                }
            }
            plan.accountPlatform = accountClass.getMethod(platformGetterName);
            plan.accountId = accountClass.getMethod(idGetterName);
        } catch (Throwable throwable) {
            // 只废掉这一套
            plan.entry = null;
            plan.listAccessor = null;
            plan.accountPlatform = null;
            plan.accountId = null;
            plan.unavailable = describe(throwable);
        }
        return plan;
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
        Object client;
        try {
            client = getInstance.invoke(null);
        } catch (Throwable throwable) {
            return 0L;
        }
        if (client == null) {
            return 0L;
        }
        // 按优先级逐套尝试：新版接口在前，旧版兜底在后
        for (QueryPlan plan : plans) {
            long qq = plan.query(client, playerName);
            if (qq > 0) {
                return qq;
            }
        }
        return 0L;
    }

    /**
     * 一套「怎么从 BridgeClient 问出 QQ 号」的方案。
     * <p>
     * 各版本的接口形状不一样（有的返回账号列表、有的只返回一个），
     * 所以把「调哪个方法」「结果里怎么取账号」「账号里怎么取平台与号」都存成句柄，
     * 运行期按需组合。任意一项解析失败只废掉这一套。
     */
    private static final class QueryPlan {

        /** 方案名，取自 EasyBot 的 operation 名，便于对着日志看。 */
        final String label;
        /** BridgeClient 上的入口方法。为 null 表示这套不可用。 */
        Method entry;
        /** 从结果对象取账号列表；为 null 表示结果对象本身就是账号。 */
        Method listAccessor;
        Method accountPlatform;
        Method accountId;
        /** 不可用原因；可用时为空串。 */
        String unavailable = "";

        QueryPlan(String label) {
            this.label = label;
        }

        boolean isUsable() {
            return entry != null;
        }

        /** 查指定玩家的 QQ 号，查不到返回 0。 */
        long query(Object client, String playerName) {
            if (!isUsable()) {
                return 0L;
            }
            try {
                Object result = entry.invoke(client, playerName);
                if (result == null) {
                    return 0L;
                }
                if (listAccessor == null) {
                    return read(result);
                }
                Object accounts = listAccessor.invoke(result);
                if (!(accounts instanceof List)) {
                    return 0L;
                }
                for (Object account : (List<?>) accounts) {
                    long qq = read(account);
                    if (qq > 0) {
                        return qq;
                    }
                }
            } catch (Throwable ignored) {
                // 未绑定、RPC 超时、EasyBot 未连接等都会走到这里，静默降级
            }
            return 0L;
        }

        /** 从一个账号对象里取 QQ 号；平台不是 qq 时返回 0。 */
        private long read(Object account) {
            if (account == null || accountPlatform == null || accountId == null) {
                return 0L;
            }
            try {
                Object platform = accountPlatform.invoke(account);
                if (platform != null && !"qq".equalsIgnoreCase(String.valueOf(platform))) {
                    return 0L;
                }
                return parseQq(String.valueOf(accountId.invoke(account)));
            } catch (Throwable throwable) {
                return 0L;
            }
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
