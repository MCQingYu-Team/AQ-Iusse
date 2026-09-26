package cn.aqcraft.iusse.net;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小实现的 WebSocket 连接（RFC 6455）。
 * <p>
 * 同一份代码支持两个方向：
 * <ul>
 *   <li><b>服务端</b>：插件监听端口，NapCat 主动连过来（反向 WS），走 {@link #handshake()}</li>
 *   <li><b>客户端</b>：插件主动连 NapCat 的 WS 服务端（正向 WS），走
 *       {@link #clientHandshake(String, int, String, String, int)}</li>
 * </ul>
 * 只实现 OneBot 用得到的部分：文本帧、Ping/Pong、关闭帧。
 */
public class WebSocketConnection {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** RFC 6455 规定的握手魔法字符串。 */
    private static final String HANDSHAKE_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;

    private static final SecureRandom RANDOM = new SecureRandom();

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    /** 客户端模式下发出的帧必须加掩码（RFC 6455 §5.3），否则对端会当作协议错误断开。 */
    private final boolean maskOutgoing;
    private volatile boolean open = true;

    public WebSocketConnection(Socket socket) throws IOException {
        this(socket, false);
    }

    public WebSocketConnection(Socket socket, boolean maskOutgoing) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
        this.maskOutgoing = maskOutgoing;
    }

    public String getRemoteAddress() {
        return String.valueOf(socket.getRemoteSocketAddress());
    }

    public boolean isOpen() {
        return open && !socket.isClosed();
    }

    /**
     * 完成 HTTP Upgrade 握手。
     *
     * @return 请求头（key 全小写）；不是合法的 WebSocket 请求时返回 {@code null}
     */
    public Map<String, String> handshake() throws IOException {
        Map<String, String> headers = new LinkedHashMap<String, String>();
        String requestLine = readLine();
        if (requestLine == null) {
            return null;
        }
        headers.put(":request-line", requestLine);

        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0) {
                headers.put(line.substring(0, separator).trim().toLowerCase(), line.substring(separator + 1).trim());
            }
        }

        String key = headers.get("sec-websocket-key");
        if (key == null || key.isEmpty()) {
            writeRaw("HTTP/1.1 400 Bad Request\r\nConnection: close\r\n\r\n");
            return null;
        }

        String accept = base64(sha1(key + HANDSHAKE_MAGIC));
        writeRaw("HTTP/1.1 101 Switching Protocols\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n");
        return headers;
    }

    /**
     * 作为客户端发起 WebSocket 握手（插件主动连 NapCat 的「WebSocket 服务端」）。
     *
     * @param token 非空时以 {@code Authorization: Bearer} 头带上
     * @return 握手成功返回 true；对端未返回 101 时返回 false
     */
    public boolean clientHandshake(String host, int port, String path, String token, int timeoutMillis)
            throws IOException {
        socket.setSoTimeout(Math.max(1000, timeoutMillis));

        byte[] nonce = new byte[16];
        RANDOM.nextBytes(nonce);
        String key = Base64.getEncoder().encodeToString(nonce);

        StringBuilder request = new StringBuilder(256);
        request.append("GET ").append(path == null || path.isEmpty() ? "/" : path).append(" HTTP/1.1\r\n");
        request.append("Host: ").append(host).append(':').append(port).append("\r\n");
        request.append("Upgrade: websocket\r\n");
        request.append("Connection: Upgrade\r\n");
        request.append("Sec-WebSocket-Key: ").append(key).append("\r\n");
        request.append("Sec-WebSocket-Version: 13\r\n");
        if (token != null && !token.isEmpty()) {
            request.append("Authorization: Bearer ").append(token).append("\r\n");
        }
        request.append("\r\n");
        writeRaw(request.toString());

        String statusLine = readLine();
        if (statusLine == null || statusLine.indexOf("101") < 0) {
            return false;
        }
        String accept = base64(sha1(key + HANDSHAKE_MAGIC));
        boolean acceptMatched = false;
        String line;
        while ((line = readLine()) != null && !line.isEmpty()) {
            int separator = line.indexOf(':');
            if (separator > 0
                    && "sec-websocket-accept".equalsIgnoreCase(line.substring(0, separator).trim())
                    && accept.equals(line.substring(separator + 1).trim())) {
                acceptMatched = true;
            }
        }
        if (!acceptMatched) {
            return false;
        }
        socket.setSoTimeout(0);
        return true;
    }

    /**
     * 阻塞读取下一条文本消息。
     * <p>
     * 收到关闭帧或连接断开时返回 {@code null}；Ping 会自动回 Pong，其它帧被忽略。
     */
    public String readMessage() throws IOException {
        while (open) {
            int first = in.read();
            if (first < 0) {
                open = false;
                return null;
            }
            int opcode = first & 0x0F;

            int second = readByte();
            boolean masked = (second & 0x80) != 0;
            long length = second & 0x7F;
            if (length == 126) {
                length = ((long) readByte() << 8) | readByte();
            } else if (length == 127) {
                length = 0;
                for (int index = 0; index < 8; index++) {
                    length = (length << 8) | readByte();
                }
            }
            if (length < 0 || length > 8 * 1024 * 1024L) {
                close();
                return null;
            }

            byte[] mask = masked ? readBytes(4) : null;
            byte[] payload = readBytes((int) length);
            if (masked && mask != null) {
                for (int index = 0; index < payload.length; index++) {
                    payload[index] ^= mask[index % 4];
                }
            }

            switch (opcode) {
                case OPCODE_TEXT:
                    return new String(payload, UTF_8);
                case OPCODE_CLOSE:
                    close();
                    return null;
                case OPCODE_PING:
                    sendFrame(0x0A, payload);
                    break;
                default:
                    break;
            }
        }
        return null;
    }

    /** 发送一条文本消息。 */
    public void sendText(String text) throws IOException {
        sendFrame(OPCODE_TEXT, text.getBytes(UTF_8));
    }

    /** 关闭连接。 */
    public void close() {
        if (!open && socket.isClosed()) {
            return;
        }
        open = false;
        try {
            sendFrame(OPCODE_CLOSE, new byte[0]);
        } catch (IOException ignored) {
            // 对端可能已经断开
        }
        Http.close(socket);
    }

    // ------------------------------------------------------------------
    // 内部
    // ------------------------------------------------------------------

    private void sendFrame(int opcode, byte[] payload) throws IOException {
        if (socket.isClosed()) {
            throw new IOException("连接已关闭");
        }

        byte[] mask = null;
        byte[] data = payload;
        if (maskOutgoing) {
            mask = new byte[4];
            RANDOM.nextBytes(mask);
            data = new byte[payload.length];
            for (int index = 0; index < payload.length; index++) {
                data[index] = (byte) (payload[index] ^ mask[index % 4]);
            }
        }

        ByteArrayOutputStream frame = new ByteArrayOutputStream(data.length + 14);
        frame.write(0x80 | opcode);
        int maskBit = maskOutgoing ? 0x80 : 0x00;
        if (data.length < 126) {
            frame.write(maskBit | data.length);
        } else if (data.length <= 0xFFFF) {
            frame.write(maskBit | 126);
            frame.write((data.length >> 8) & 0xFF);
            frame.write(data.length & 0xFF);
        } else {
            frame.write(maskBit | 127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) data.length >> shift) & 0xFF));
            }
        }
        if (mask != null) {
            frame.write(mask, 0, 4);
        }
        frame.write(data, 0, data.length);

        synchronized (writeLock) {
            out.write(frame.toByteArray());
            out.flush();
        }
    }

    private void writeRaw(String text) throws IOException {
        synchronized (writeLock) {
            out.write(text.getBytes(UTF_8));
            out.flush();
        }
    }

    private int readByte() throws IOException {
        int value = in.read();
        if (value < 0) {
            throw new IOException("连接已被对端关闭");
        }
        return value;
    }

    private byte[] readBytes(int count) throws IOException {
        byte[] buffer = new byte[count];
        int offset = 0;
        while (offset < count) {
            int read = in.read(buffer, offset, count - offset);
            if (read < 0) {
                throw new IOException("连接已被对端关闭");
            }
            offset += read;
        }
        return buffer;
    }

    /** 读一行（以 \n 结束），用于解析握手请求头。 */
    private String readLine() throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream(128);
        int value;
        boolean any = false;
        while ((value = in.read()) != -1) {
            any = true;
            if (value == '\n') {
                break;
            }
            if (value != '\r') {
                buffer.write(value);
            }
        }
        if (!any) {
            return null;
        }
        return new String(buffer.toByteArray(), UTF_8);
    }

    private static String base64(byte[] data) {
        return Base64.getEncoder().encodeToString(data);
    }

    private static byte[] sha1(String text) {
        try {
            return MessageDigest.getInstance("SHA-1").digest(text.getBytes(UTF_8));
        } catch (NoSuchAlgorithmException e) {
            // SHA-1 是 JDK 强制实现的算法，不可能走到这里
            throw new IllegalStateException(e);
        }
    }
}
