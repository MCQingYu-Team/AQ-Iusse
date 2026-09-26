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
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 最小实现的 WebSocket 服务端连接（RFC 6455）。
 * <p>
 * JDK 只提供 WebSocket <b>客户端</b>，而 OneBot 的反向 WebSocket 需要插件当服务端，
 * 所以这里手写握手与帧编解码。只实现 OneBot 用得到的部分：
 * 文本帧、Ping/Pong、关闭帧。
 */
public class WebSocketConnection {

    private static final Charset UTF_8 = Charset.forName("UTF-8");

    /** RFC 6455 规定的握手魔法字符串。 */
    private static final String HANDSHAKE_MAGIC = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";

    private static final int OPCODE_TEXT = 0x1;
    private static final int OPCODE_CLOSE = 0x8;
    private static final int OPCODE_PING = 0x9;

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private final Object writeLock = new Object();
    private volatile boolean open = true;

    public WebSocketConnection(Socket socket) throws IOException {
        this.socket = socket;
        this.socket.setTcpNoDelay(true);
        this.in = new BufferedInputStream(socket.getInputStream());
        this.out = new BufferedOutputStream(socket.getOutputStream());
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
        ByteArrayOutputStream frame = new ByteArrayOutputStream(payload.length + 10);
        frame.write(0x80 | opcode);
        if (payload.length < 126) {
            frame.write(payload.length);
        } else if (payload.length <= 0xFFFF) {
            frame.write(126);
            frame.write((payload.length >> 8) & 0xFF);
            frame.write(payload.length & 0xFF);
        } else {
            frame.write(127);
            for (int shift = 56; shift >= 0; shift -= 8) {
                frame.write((int) (((long) payload.length >> shift) & 0xFF));
            }
        }
        frame.write(payload, 0, payload.length);

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
