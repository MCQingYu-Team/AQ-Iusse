package cn.aqcraft.iusse.channel;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
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
 * OneBot（QQ）通道：以**反向 WebSocket** 接入。
 * <p>
 * 插件自己当 WebSocket 服务端，由 NapCat / go-cqhttp 主动连过来。
 * NapCat 侧配置示例：
 *
 * <pre>
 * "reverseWs": { "enable": true, "urls": ["ws://127.0.0.1:6700/onebot"] }
 * </pre>
 *
 * 连接建立后，插件通过 {@code send_group_msg} 把反馈发到指定群。
 */
public class OneBotChannel implements Channel {

    public static final String ID = "onebot";

    /** QQ 单条消息不要太长，超出会被服务端截断。 */
    private static final int MAX_MESSAGE_LENGTH = 1500;

    private final AqIssuePlugin plugin;
    private final PluginConfig config;
    private final Set<Connection> connections =
            Collections.newSetFromMap(new ConcurrentHashMap<Connection, Boolean>());

    private volatile ServerSocket serverSocket;
    private volatile boolean running;
    private final String disabledReason;

    public OneBotChannel(AqIssuePlugin plugin, PluginConfig config) {
        this.plugin = plugin;
        this.config = config;

        String reason = null;
        if (!config.isOneBotEnabled()) {
            reason = "配置中未启用";
        } else if (config.getOneBotPort() <= 0 || config.getOneBotPort() > 65535) {
            reason = "端口不合法";
        } else if (config.getOneBotGroupIds().isEmpty()) {
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
        ServerSocket server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(config.getOneBotBind(), config.getOneBotPort()));
        this.serverSocket = server;
        this.running = true;

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

    @Override
    public void stop() {
        running = false;
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
        connection.reader = reader;
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
            plugin.getLogger().info("OneBot 客户端已断开：" + connection.remote());
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
                return;
            }
            CompletableFuture<Map<String, Object>> future = connection.pending.remove(echo);
            if (future != null) {
                future.complete(json);
            }
        } catch (RuntimeException ignored) {
            // 非 JSON 帧直接忽略
        }
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
    public String submit(Submission submission) throws IOException {
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
        return "已发送到 " + sent + " 个 QQ 群";
    }

    @Override
    public String checkStatus() {
        int count = connections.size();
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

    private String buildMessage(Submission submission) {
        String header = "【" + submission.getCategoryName() + "】" + submission.getTitle();
        String text = header + "\n" + submission.toPlainText();
        return Text.truncate(text, MAX_MESSAGE_LENGTH);
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
        volatile Thread reader;

        Connection(WebSocketConnection ws) {
            this.ws = ws;
        }

        String remote() {
            return ws.getRemoteAddress();
        }
    }
}
