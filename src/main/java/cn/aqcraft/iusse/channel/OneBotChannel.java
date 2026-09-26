package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.logging.Level;

import cn.aqcraft.iusse.AqIssuePlugin;
import cn.aqcraft.iusse.config.PluginConfig;
import cn.aqcraft.iusse.github.MiniJson;
import cn.aqcraft.iusse.net.WebSocketConnection;
import cn.aqcraft.iusse.util.Text;

/**
 * OneBot（QQ）通道，支持两种接法：
 * <ul>
 *   <li>{@code mode: server}（默认）—— <b>反向 WebSocket</b>：插件自己当服务端监听端口，
 *       由 NapCat 主动连过来。NapCat 侧配「WebSocket 客户端」：
 *       <pre>"reverseWs": { "enable": true, "urls": ["ws://127.0.0.1:6700/onebot"] }</pre></li>
 *   <li>{@code mode: client} —— <b>正向 WebSocket</b>：插件主动去连 NapCat 的
 *       「WebSocket 服务端」。适用于插件所在机器不能被 NapCat 访问、
 *       但 NapCat 那台有公网端口映射的场景。NapCat 侧配「WebSocket 服务端」，
 *       插件填 {@code url: "ws://host:port"}。</li>
 * </ul>
 * 两种模式下发消息都是 {@code send_group_msg}。
 */
public class OneBotChannel implements Channel {

    public static final String ID = "onebot";

    /** QQ 单条消息不要太长，超出会被服务端截断。 */
    private static final int MAX_MESSAGE_LENGTH = 1500;

    /** 断线重连间隔。 */
    private static final long RECONNECT_INTERVAL_MILLIS = 5000L;

    private final AqIssuePlugin plugin;
    private final PluginConfig config;
    private final Set<Connection> connections =
            Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());

    private volatile ServerSocket serverSocket;
    private volatile Thread clientThread;
    private volatile boolean running;
    /** 已经就 token / 权限问题提示过一次，避免每 5 秒重连都刷屏。 */
    private volatile boolean rejectionLogged;
    private final String disabledReason;

    public OneBotChannel(AqIssuePlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;

        String reason = null;
        if (!config.isOneBotEnabled()) {
            reason = "配置中未启用";
        } else if (config.isOneBotClientMode()) {
            String url = config.getOneBotUrl();
            if (url.isEmpty()) {
                reason = "client 模式下必须填写 channels.onebot.url";
            } else if (url.startsWith("wss://")) {
                reason = "暂不支持 wss://，请改用 ws://";
            } else if (!url.startsWith("ws://")) {
                reason = "url 必须以 ws:// 开头";
            }
        } else if (config.getOneBotPort() <= 0 || config.getOneBotPort() > 65535) {
            reason = "端口不合法";
        }
        if (reason == null && config.getOneBotGroupIds().isEmpty()) {
            reason = "未配置 channels.onebot.group-ids（要发到哪个群）";
        }
        this.disabledReason = reason;
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getDisplayName() {
        return "QQ 群";
    }

    @Override
    public boolean isEnabled() {
        return disabledReason == null;
    }

    @Override
    public String getDisabledReason() {
        return disabledReason == null ? "" : disabledReason;
    }

    // ------------------------------------------------------------------
    // 生命周期
    // ------------------------------------------------------------------

    @Override
    public void start() throws IOException {
        this.running = true;
        if (config.isOneBotClientMode()) {
            startClient();
        } else {
            startServer();
        }
    }

    /** 反向 WS：插件监听端口，等 NapCat 连过来。 */
    private void startServer() throws IOException {
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(config.getOneBotBind(), config.getOneBotPort()));
        this.serverSocket = server;

        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                acceptLoop();
            }
        }, "AQIssue-OneBot-Accept");
        thread.setDaemon(true);
        thread.start();

        plugin.getLogger().info("OneBot 反向 WebSocket 已监听 "
                + config.getOneBotBind() + ":" + config.getOneBotPort() + config.getOneBotPath()
                + "（等待 NapCat 连接）");
    }

    /** 正向 WS：插件主动去连 NapCat 的 WebSocket 服务端。 */
    private void startClient() {
        Thread thread = new Thread(new Runnable() {
            @Override
            public void run() {
                connectLoop();
            }
        }, "AQIssue-OneBot-Connect");
        thread.setDaemon(true);
        this.clientThread = thread;
        thread.start();
        plugin.getLogger().info("OneBot 客户端模式：准备连接 " + config.getOneBotUrl());
    }

    /** 断线重连循环。 */
    private void connectLoop() {
        while (running) {
            try {
                openConnection();
            } catch (Exception e) {
                if (running) {
                    String message = e.getMessage();
                    plugin.getLogger().warning("连接 OneBot 服务端失败（" + config.getOneBotUrl() + "）："
                            + (message == null || message.isEmpty() ? e.getClass().getSimpleName() : message)
                            + "，" + (RECONNECT_INTERVAL_MILLIS / 1000L) + " 秒后重试");
                }
            }
            if (!running) {
                return;
            }
            try {
                Thread.sleep(RECONNECT_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 建立一次连接，然后阻塞读取直到断开。 */
    private void openConnection() throws IOException {
        URI uri = URI.create(config.getOneBotUrl());
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IOException("url 解析失败，应形如 ws://host:port");
        }
        int port = uri.getPort() > 0 ? uri.getPort() : 80;
        String path = uri.getPath() == null || uri.getPath().isEmpty() ? "/" : uri.getPath();

        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port),
                (int) Math.min(config.getTimeoutMillis(), 10000L));

        WebSocketConnection ws = new WebSocketConnection(socket, true);
        if (!ws.clientHandshake(host, port, path, config.getOneBotAccessToken(),
                (int) config.getTimeoutMillis())) {
            ws.close();
            throw new IOException("握手被拒绝，请检查路径与 access-token");
        }

        Connection connection = new Connection(ws);
        connections.add(connection);
        plugin.getLogger().info("已连接到 OneBot 服务端 " + config.getOneBotUrl());
        readLoop(connection);
    }

    @Override
    public void stop() {
        running = false;
        Thread thread = clientThread;
        if (thread != null) {
            thread.interrupt();
            clientThread = null;
        }
        ServerSocket server = serverSocket;
        if (server != null) {
            try {
                server.close();
            } catch (IOException ignored) {
                // 关闭失败无所谓
            }
        }
        serverSocket = null;
        for (Connection connection : new ArrayList<Connection>(connections)) {
            connection.ws.close();
        }
        connections.clear();
    }

    /** 连接数，供 /iusse status 展示。 */
    public int getConnectionCount() {
        return connections.size();
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket server = serverSocket;
                if (server == null) {
                    return;
                }
                handle(server.accept());
            } catch (IOException e) {
                if (running) {
                    plugin.getLogger().log(Level.WARNING, "OneBot 接受连接失败", e);
                }
            }
        }
    }

    private void handle(Socket socket) {
        Connection connection;
        try {
            WebSocketConnection ws = new WebSocketConnection(socket);
            Map<String, String> headers = ws.handshake();
            if (headers == null) {
                ws.close();
                return;
            }
            if (!isAuthorized(headers)) {
                plugin.getLogger().warning("OneBot 连接因 access-token 不匹配被拒绝：" + ws.getRemoteAddress());
                ws.close();
                return;
            }
            connection = new Connection(ws);
        } catch (IOException e) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // 忽略
            }
            return;
        }

        connections.add(connection);
        plugin.getLogger().info("OneBot 客户端已连接：" + connection.remote());

        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                readLoop(connection);
            }
        }, "AQIssue-OneBot-Reader");
        reader.setDaemon(true);
        reader.start();
    }

    private void readLoop(Connection connection) {
        try {
            while (running && connection.ws.isOpen()) {
                String text = connection.ws.readMessage();
                if (text == null) {
                    break;
                }
                dispatch(connection, text);
            }
        } catch (IOException ignored) {
            // 断开是常态
        } finally {
            connections.remove(connection);
            connection.ws.close();
            String message = "OneBot 连接已断开：" + connection.remote();
            // 刚连上就被断开多半是配置问题（如 token 不对），
            // 此时错误帧已经以 WARNING 形式提示过，不必再刷一条 INFO
            if (System.currentTimeMillis() - connection.connectedAt < 3000L) {
                plugin.getLogger().fine(message + "（连接仅存活不到 3 秒）");
            } else {
                plugin.getLogger().info(message);
            }
        }
    }

    /** 把带 echo 的 API 响应交给等待中的调用方。 */
    private void dispatch(Connection connection, String text) {
        if (text.isEmpty() || text.charAt(0) != '{') {
            return;
        }
        try {
            Map<String, Object> json = MiniJson.parseObject(text);
            String echo = MiniJson.string(json, "echo");
            if (echo == null) {
                // 没有 echo 的不是响应，而是事件或服务端主动报错
                reportServerMessage(json);
                return;
            }
            CompletableFuture<Map<String, Object>> future = connection.pending.remove(echo);
            if (future != null) {
                rejectionLogged = false;
                future.complete(json);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 帧直接忽略
        }
    }

    /** 把服务端主动推送的失败信息（如 token 校验失败）记到日志。 */
    private void reportServerMessage(Map<String, Object> json) {
        if (!"failed".equalsIgnoreCase(MiniJson.string(json, "status"))) {
            return;
        }
        String wording = MiniJson.string(json, "wording");
        if (wording == null || wording.isEmpty()) {
            wording = MiniJson.string(json, "message");
        }
        if (rejectionLogged) {
            return;
        }
        rejectionLogged = true;
        plugin.getLogger().warning("OneBot 服务端拒绝了本连接：" + (wording == null ? "未知原因" : wording)
                + "（retcode " + MiniJson.integer(json, "retcode", -1) + "）"
                + "。请检查 channels.onebot.access-token 是否与 NapCat 侧完全一致。");
    }

    private boolean isAuthorized(Map<String, String> headers) {
        String expected = config.getOneBotAccessToken();
        if (expected.isEmpty()) {
            return true;
        }
        String authorization = headers.get("authorization");
        if (authorization != null && authorization.startsWith("Bearer ")
                && expected.equals(authorization.substring(7).trim())) {
            return true;
        }
        // 也兼容 NapCat 把 token 放在 query string 的写法
        String requestLine = headers.get(":request-line");
        if (requestLine != null) {
            int index = requestLine.indexOf("access_token=");
            if (index >= 0) {
                String value = requestLine.substring(index + "access_token=".length());
                int end = value.indexOf(' ');
                if (end > 0) {
                    value = value.substring(0, end);
                }
                return expected.equals(value);
            }
        }
        return false;
    }

    // ------------------------------------------------------------------
    // 投递
    // ------------------------------------------------------------------

    @Override
    public ChannelResult submit(Submission submission) throws IOException {
        Connection connection = pickConnection();
        if (connection == null) {
            throw new IOException("当前没有 OneBot 客户端连接（请检查 NapCat 的反向 WS 配置）");
        }

        List<Long> groups = config.getOneBotGroupIds();
        String message = buildMessage(submission);

        int sent = 0;
        String lastError = null;
        for (Long groupId : groups) {
            Map<String, Object> params = new LinkedHashMap<String, Object>();
            params.put("group_id", groupId);
            params.put("message", message);

            Map<String, Object> result = call(connection, "send_group_msg", params, config.getOneBotTimeoutMillis());
            if (isOk(result)) {
                sent++;
            } else {
                lastError = describeFailure(groupId, result);
            }
        }

        if (sent == 0) {
            throw new IOException(lastError == null ? "OneBot 未返回结果" : lastError);
        }
        return ChannelResult.success(this, "已发送到 " + sent + " 个 QQ 群");
    }

    @Override
    public String checkStatus() {
        int count = connections.size();
        if (config.isOneBotClientMode()) {
            return count > 0
                    ? "已连接到 " + config.getOneBotUrl()
                    : "未连接（正在重试 " + config.getOneBotUrl() + "）";
        }
        if (count == 0) {
            return "已监听 " + config.getOneBotBind() + ":" + config.getOneBotPort() + "，但暂无客户端连接";
        }
        return count + " 个 OneBot 客户端已连接";
    }

    private Connection pickConnection() {
        for (Connection connection : connections) {
            if (connection.ws.isOpen()) {
                return connection;
            }
        }
        return null;
    }

    /**
     * 拼装群消息。
     * <p>
     * 正文超长时只截断正文，末尾的链接一定要保住 —— 否则玩家看不到 Issue 地址。
     */
    private String buildMessage(Submission submission) {
        StringBuilder suffix = new StringBuilder();
        for (String link : submission.getLinks()) {
            suffix.append('\n').append(link);
        }

        String header = "【" + submission.getCategoryName() + "】" + submission.getTitle() + "\n";
        String content = header + submission.toPlainText(false);

        int budget = MAX_MESSAGE_LENGTH - suffix.length();
        if (budget < 64) {
            budget = 64;
        }
        return Text.truncate(content, budget) + suffix;
    }

    private Map<String, Object> call(Connection connection, String action,
                                     Map<String, Object> params, long timeoutMillis) throws IOException {
        String echo = UUID.randomUUID().toString();
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("action", action);
        payload.put("params", params);
        payload.put("echo", echo);

        CompletableFuture<Map<String, Object>> future = new CompletableFuture<Map<String, Object>>();
        connection.pending.put(echo, future);
        try {
            connection.ws.sendText(MiniJson.write(payload));
            return future.get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            throw new IOException("OneBot 调用失败：" + e.getMessage(), e);
        } finally {
            connection.pending.remove(echo);
        }
    }

    private static boolean isOk(Map<String, Object> result) {
        if (result == null) {
            return false;
        }
        if ("ok".equalsIgnoreCase(MiniJson.string(result, "status"))) {
            return true;
        }
        return MiniJson.integer(result, "retcode", -1) == 0;
    }

    private static String describeFailure(Long groupId, Map<String, Object> result) {
        if (result == null) {
            return "群 " + groupId + "：OneBot 未在超时时间内响应";
        }
        String wording = MiniJson.string(result, "wording");
        if (wording == null) {
            wording = MiniJson.string(result, "message");
        }
        int retcode = MiniJson.integer(result, "retcode", -1);
        return "群 " + groupId + " 发送失败（retcode " + retcode + "）"
                + (wording == null ? "" : "：" + wording);
    }

    /** 一条 OneBot 连接。 */
    private static final class Connection {

        final WebSocketConnection ws;
        final Map<String, CompletableFuture<Map<String, Object>>> pending =
                new ConcurrentHashMap<String, CompletableFuture<Map<String, Object>>>();
        final long connectedAt = System.currentTimeMillis();

        Connection(WebSocketConnection ws) {
            this.ws = ws;
        }

        String remote() {
            return ws.getRemoteAddress();
        }
    }
}
