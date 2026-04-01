package org.example;

import com.aayushatharva.brotli4j.Brotli4jLoader;
import com.aayushatharva.brotli4j.decoder.BrotliInputStream;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import pojo.Credential;
import com.gitolk.BiliLiveSongService;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.cert.X509Certificate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class BiliDanmuWebSocketClient extends WebSocketClient {
    private static final Map<String, String> headers;

    static {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0");
        headers.put("Referer", "https://www.bilibili.com");
        Brotli4jLoader.ensureAvailability();
    }

    private static final long HEARTBEAT_INTERVAL_SECONDS = 30L;
    private static final long BASE_RECONNECT_DELAY_SECONDS = 3L;
    private static final long MAX_RECONNECT_DELAY_SECONDS = 60L;

    private final Credential credential;
    private final BiliLiveSongService songService = new BiliLiveSongService();
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    private volatile ScheduledFuture<?> heartbeatTask;
    private volatile int reconnectAttempts = 0;

    public BiliDanmuWebSocketClient(URI serverUri, Credential credential) {
        super(serverUri, headers);
        this.credential = credential;
        if (!"wss".equalsIgnoreCase(serverUri.getScheme())) {
            return;
        }
        try {
            SSLContext sslContext = SSLContext.getInstance("TLS");
            sslContext.init(null, new TrustManager[]{new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String authType) {
                }

                public void checkServerTrusted(X509Certificate[] chain, String authType) {
                }

                public X509Certificate[] getAcceptedIssuers() {
                    return new X509Certificate[0];
                }
            }}, new java.security.SecureRandom());

            setSocketFactory(sslContext.getSocketFactory());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onOpen(ServerHandshake handShakeData) {
        try {
            send(generateAuthPack(credential));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        System.out.println("连接成功");
        reconnectAttempts = 0;
        reconnectScheduled.set(false);
        startHeartbeat();
    }

    private void startHeartbeat() {
        stopHeartbeat();
        heartbeatTask = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (!isOpen()) {
                return;
            }
            try {
                ByteBuffer heartBeatPack = ByteBuffer.wrap(generateHeartBeatPack());
                send(heartBeatPack);
            } catch (Exception e) {
                System.err.println("发送心跳失败: " + e.getMessage());
            }
        }, 0, HEARTBEAT_INTERVAL_SECONDS, TimeUnit.SECONDS);
    }

    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(true);
            heartbeatTask = null;
        }
    }

    @Override
    public void onMessage(String s) {
    }

    public void onMessage(ByteBuffer byteBuffer) {
        try {
            this.unpack(byteBuffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("连接关闭，原因: " + reason + ", code: " + code + ", remote: " + remote);
        stopHeartbeat();
        scheduleReconnect();
    }

    @Override
    public void onError(Exception ex) {
        System.out.println("发生错误: " + ex.getMessage());
        if (!isOpen()) {
            scheduleReconnect();
        }
    }

    private void scheduleReconnect() {
        if (!reconnectScheduled.compareAndSet(false, true)) {
            return;
        }

        long delaySeconds = Math.min(
                BASE_RECONNECT_DELAY_SECONDS * (1L << Math.min(reconnectAttempts, 5)),
                MAX_RECONNECT_DELAY_SECONDS
        );
        int attemptNo = ++reconnectAttempts;
        System.out.println("将在 " + delaySeconds + " 秒后尝试重连，第 " + attemptNo + " 次");

        reconnectExecutor.schedule(() -> {
            try {
                reconnect();
            } catch (Exception e) {
                System.err.println("触发重连失败: " + e.getMessage());
            } finally {
                reconnectScheduled.set(false);
                if (!isOpen()) {
                    scheduleReconnect();
                }
            }
        }, delaySeconds, TimeUnit.SECONDS);
    }

    /** info[1] 在部分协议下为 String，也可能为分段数组，统一抽出可见文本用于点歌与聊天 */
    private static String extractDanmuPlainText(JSONArray infoArray) {
        if (infoArray == null || infoArray.size() < 2) {
            return "";
        }
        Object v = infoArray.get(1);
        if (v == null) {
            return "";
        }
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof JSONArray) {
            JSONArray arr = (JSONArray) v;
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < arr.size(); i++) {
                Object o = arr.get(i);
                if (o instanceof String) {
                    sb.append((String) o);
                }
            }
            return sb.toString();
        }
        return String.valueOf(v);
    }

    public static URI generateUri(JSONObject conf) {
        JSONArray hostList = conf.getJSONArray("host_list");
        JSONObject host = hostList.getJSONObject(0);
        String wssUrl = String.format("wss://%s:%s/sub", host.getString("host"), host.getString("wss_port"));
        System.out.println(wssUrl);
        return URI.create(wssUrl);
    }

    public byte[] generateAuthPack(Credential credential) throws IOException {
        return pack(credential.getCredential().toString(), Opt.AUTH);
    }

    public static byte[] generateHeartBeatPack() throws IOException {
        return pack(null, Opt.HEARTBEAT);
    }

    public static byte[] pack(String jsonStr, short code) throws IOException {
        byte[] contentBytes = new byte[0];
        if (Opt.AUTH == code) {
            contentBytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        }
        try (ByteArrayOutputStream data = new ByteArrayOutputStream();
             DataOutputStream stream = new DataOutputStream(data)) {
            stream.writeInt(contentBytes.length + 16);
            stream.writeShort(16);
            stream.writeShort(1);
            stream.writeInt(code);
            stream.writeInt(1);
            if (Opt.AUTH == code) {
                stream.writeBytes(jsonStr);
            }
            return data.toByteArray();
        }
    }

    public void unpack(ByteBuffer byteBuffer) throws IOException {
        int packageLen = byteBuffer.getInt();
        short headLength = byteBuffer.getShort();
        short protVer = byteBuffer.getShort();
        int optCode = byteBuffer.getInt();
        byteBuffer.getInt(); // skip sequence

        if (Opt.HEARTBEAT_REPLY == optCode) {
            System.out.println("这是服务端心跳回包");
        }
        byte[] contentBytes = new byte[packageLen - headLength];
        byteBuffer.get(contentBytes);

        if (Version.BROTLI == protVer) {
            unpack(ByteBuffer.wrap(decompressBrotli(contentBytes)));
            return;
        }

        String content = new String(contentBytes, StandardCharsets.UTF_8);
        if (Opt.AUTH_REPLY == optCode) {
            System.out.println("这是认证回包");
        }

        if (Opt.SEND_SMS_REPLY == optCode) {
            JSONObject jsonObject = JSONObject.parseObject(content);
            Object cmdObj = jsonObject.get("cmd");
            String cmd = cmdObj != null ? cmdObj.toString() : "";
            // B 站部分版本 cmd 为 DANMU_MSG:4:1:1:1 等，不能再用 equals
            if (cmd.startsWith("DANMU_MSG")) {
                JSONArray infoArray = jsonObject.getJSONArray("info");
                String danmuContent = extractDanmuPlainText(infoArray);

                JSONArray userInfo = infoArray.getJSONArray(2);
                String username = userInfo != null && userInfo.size() > 1 ? userInfo.getString(1) : "匿名";

                LiveChatBuffer.getInstance().append(username, danmuContent);

                System.out.println("用户: " + username);
                System.out.println("弹幕内容: " + danmuContent);

                try {
                    boolean handled = songService.userAddSong(danmuContent, username);
                    if (handled) {
                        System.out.println("点歌已处理: " + danmuContent);
                    } else {
                        System.out.println("未作为点歌处理（格式不符）: " + danmuContent);
                    }
                } catch (Exception ex) {
                    System.err.println("点歌处理失败: " + ex.getMessage());
                }
            }
        }

        if (byteBuffer.position() < byteBuffer.limit()) {
            unpack(byteBuffer);
        }
    }

    static final class Opt {
        static final short HEARTBEAT = 2;
        static final short HEARTBEAT_REPLY = 3;
        static final short SEND_SMS_REPLY = 5;
        static final short AUTH = 7;
        static final short AUTH_REPLY = 8;
        private Opt() {}
    }

    static final class Version {
        static final short BROTLI = 3;
        private Version() {}
    }

    public static byte[] decompressBrotli(byte[] data) throws IOException {
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data);
             BrotliInputStream brotliInputStream = new BrotliInputStream(bais);
             ByteArrayOutputStream baos = new ByteArrayOutputStream()) {

            byte[] buffer = new byte[1024];
            int len;
            while ((len = brotliInputStream.read(buffer)) != -1) {
                baos.write(buffer, 0, len);
            }
            return baos.toByteArray();
        }
    }
}
