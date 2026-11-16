package dev.azad.chat.common;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * 极简 Redis 客户端：只实现 AUTH / PUBLISH / SUBSCRIBE（RESP2），不引入 Jedis 依赖。
 */
public final class RedisBus implements AutoCloseable {

    private static final String CRLF = "\r\n";
    private static final byte CR = 13;
    private static final byte LF = 10;

    private final String host;
    private final int port;
    private final String password;
    private final Logger log;

    private Socket pubSocket;
    private OutputStream pubOut;
    private InputStream pubIn;

    private volatile boolean running = true;

    public RedisBus(String host, int port, String password, Logger log) {
        this.host = host;
        this.port = port;
        this.password = password;
        this.log = log;
    }

    public synchronized void publish(String channel, String message) {
        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                if (pubSocket == null || pubSocket.isClosed()) {
                    pubSocket = open();
                    pubOut = pubSocket.getOutputStream();
                    pubIn = pubSocket.getInputStream();
                }
                writeCommand(pubOut, "PUBLISH", channel, message);
                readReply(pubIn);
                return;
            } catch (IOException e) {
                log.warning("[Azad-Chat] Redis 发布失败，准备重连: " + e);
                closeQuietly(pubSocket);
                pubSocket = null;
            }
        }
    }

    public Thread subscribe(String channel, Consumer<String> onMessage) {
        Thread t = new Thread(() -> {
            while (running) {
                try (Socket socket = open()) {
                    OutputStream out = socket.getOutputStream();
                    InputStream in = socket.getInputStream();
                    writeCommand(out, "SUBSCRIBE", channel);
                    log.info("[Azad-Chat] 已订阅 Redis 频道 " + channel);
                    while (running) {
                        Object reply = readReply(in);
                        if (reply instanceof List<?> list && list.size() >= 3
                                && "message".equals(String.valueOf(list.get(0)))) {
                            Object body = list.get(2);
                            if (body != null) {
                                onMessage.accept(String.valueOf(body));
                            }
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        log.warning("[Azad-Chat] Redis 订阅断开，3 秒后重连: " + e);
                    }
                }
                try {
                    Thread.sleep(3000L);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }, "azad-chat-redis-sub");
        t.setDaemon(true);
        t.start();
        return t;
    }

    private Socket open() throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress(host, port), 5000);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        if (password != null && !password.isBlank()) {
            writeCommand(socket.getOutputStream(), "AUTH", password);
            Object reply = readReply(socket.getInputStream());
            if (!(reply instanceof String s) || !"OK".equalsIgnoreCase(s)) {
                closeQuietly(socket);
                throw new IOException("Redis AUTH 失败: " + reply);
            }
        }
        return socket;
    }

    private static void writeCommand(OutputStream out, String... args) throws IOException {
        StringBuilder header = new StringBuilder();
        header.append('*').append(args.length).append(CRLF);
        out.write(header.toString().getBytes(StandardCharsets.US_ASCII));
        for (String arg : args) {
            byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
            out.write(('$' + Integer.toString(bytes.length) + CRLF).getBytes(StandardCharsets.US_ASCII));
            out.write(bytes);
            out.write(CR);
            out.write(LF);
        }
        out.flush();
    }

    private static Object readReply(InputStream in) throws IOException {
        int type = in.read();
        if (type == -1) {
            throw new EOFException("Redis 连接已关闭");
        }
        String line = readLine(in);
        switch (type) {
            case '+' -> {
                return line;
            }
            case '-' -> throw new IOException("Redis 返回错误: " + line);
            case ':' -> {
                return Long.parseLong(line);
            }
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    return null;
                }
                byte[] data = in.readNBytes(len);
                readLine(in);
                return new String(data, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    return null;
                }
                List<Object> list = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    list.add(readReply(in));
                }
                return list;
            }
            default -> throw new IOException("未知 RESP 类型: " + (char) type);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = in.read()) != -1) {
            if (c == CR) {
                int next = in.read();
                if (next == LF || next == -1) {
                    return sb.toString();
                }
                sb.append((char) c).append((char) next);
                continue;
            }
            sb.append((char) c);
        }
        throw new EOFException("Redis 连接已关闭");
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    @Override
    public synchronized void close() {
        running = false;
        closeQuietly(pubSocket);
        pubSocket = null;
    }
}
