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

/**
 * B站直播弹幕 WebSocket 客户端。
 * <p>
 * 负责连接 B站直播弹幕 WebSocket 服务器，接收并解析二进制弹幕数据帧。
 * 内置心跳保活、断线指数退避重连机制。
 * </p>
 * <p>
 * 收到弹幕后会：
 * <ul>
 *   <li>将弹幕文本追加到 {@link LiveChatBuffer}（用于前端展示）</li>
 *   <li>调用 {@link BiliLiveSongService} 尝试识别点歌指令</li>
 * </ul>
 * </p>
 *
 * <h3>B站弹幕协议简述</h3>
 * <pre>
 * 数据帧头（16 字节）：
 *   [0-3]   int   总包长度（含头）
 *   [4-5]   short 头长度（固定 16）
 *   [6-7]   short 协议版本（0=普通 JSON, 2=zlib, 3=Brotli）
 *   [8-11]  int   操作码（2=心跳, 3=心跳回复, 5=弹幕/通知, 7=认证, 8=认证回复）
 *   [12-15] int   序列号
 * </pre>
 */
public class BiliDanmuWebSocketClient extends WebSocketClient {

    /** 默认请求头，模拟浏览器 */
    private static final Map<String, String> headers;

    static {
        headers = new HashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0");
        headers.put("Referer", "https://www.bilibili.com");
        // 预加载 Brotli 本地库，用于解压协议版本 3 的数据帧
        Brotli4jLoader.ensureAvailability();
    }

    /** 心跳发送间隔（秒） */
    private static final long HEARTBEAT_INTERVAL_SECONDS = 30L;
    /** 重连初始延迟（秒） */
    private static final long BASE_RECONNECT_DELAY_SECONDS = 3L;
    /** 重连最大延迟（秒） */
    private static final long MAX_RECONNECT_DELAY_SECONDS = 60L;

    /** 认证凭据 */
    private final Credential credential;
    /** 点歌服务实例 */
    private final BiliLiveSongService songService = new BiliLiveSongService();
    /** 心跳定时任务线程池 */
    private final ScheduledExecutorService heartbeatExecutor = Executors.newSingleThreadScheduledExecutor();
    /** 重连定时任务线程池 */
    private final ScheduledExecutorService reconnectExecutor = Executors.newSingleThreadScheduledExecutor();
    /** 防止并发调度多个重连任务 */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);

    /** 当前心跳定时任务的 Future（用于取消） */
    private volatile ScheduledFuture<?> heartbeatTask;
    /** 已尝试的重连次数（用于指数退避） */
    private volatile int reconnectAttempts = 0;

    /**
     * 构造弹幕 WebSocket 客户端。
     * <p>
     * 若连接地址为 wss 协议，将自动配置信任所有证书的 SSL 上下文
     * （B站直播弹幕服务器证书有时会变更，此处采用宽松策略）。
     * </p>
     *
     * @param serverUri  弹幕 WebSocket 服务器地址
     * @param credential 认证凭据
     */
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

    /**
     * 连接建立回调：发送认证包，重置重连计数，启动心跳。
     */
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

    /**
     * 启动心跳定时任务：每 {@link #HEARTBEAT_INTERVAL_SECONDS} 秒发送一次心跳包。
     */
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

    /**
     * 取消当前心跳定时任务。
     */
    private void stopHeartbeat() {
        ScheduledFuture<?> task = heartbeatTask;
        if (task != null) {
            task.cancel(true);
            heartbeatTask = null;
        }
    }

    /**
     * 文本消息回调（弹幕协议不使用文本帧，此方法留空）。
     */
    @Override
    public void onMessage(String s) {
    }

    /**
     * 二进制消息回调：将收到的字节帧交给 {@link #unpack} 解析。
     */
    public void onMessage(ByteBuffer byteBuffer) {
        try {
            this.unpack(byteBuffer);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * 连接关闭回调：停止心跳并调度重连。
     */
    @Override
    public void onClose(int code, String reason, boolean remote) {
        System.out.println("连接关闭，原因: " + reason + ", code: " + code + ", remote: " + remote);
        stopHeartbeat();
        scheduleReconnect();
    }

    /**
     * 连接错误回调：若连接已断开则调度重连。
     */
    @Override
    public void onError(Exception ex) {
        System.out.println("发生错误: " + ex.getMessage());
        if (!isOpen()) {
            scheduleReconnect();
        }
    }

    /**
     * 调度一次重连。
     * <p>
     * 采用指数退避策略：延迟 = BASE * 2^min(attempts, 5)，上限为 MAX_RECONNECT_DELAY_SECONDS。
     * 使用 CAS 保证同时只有一个重连任务在调度中。
     * </p>
     */
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

    /**
     * 从弹幕 info 数组中提取纯文本内容。
     * <p>
     * info[1] 在不同协议版本下可能是 String 或分段 JSONArray，
     * 本方法统一处理为可见的纯文本字符串。
     * </p>
     *
     * @param infoArray 弹幕 info 数组
     * @return 提取出的弹幕文本
     */
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

    /**
     * 从弹幕连接信息中生成 WebSocket URI。
     * <p>
     * 取 host_list 中第一个服务器，拼接 wss 协议地址。
     * </p>
     *
     * @param conf getDanmuInfoData 返回的 data 对象
     * @return wss://host:port/sub 格式的 URI
     */
    public static URI generateUri(JSONObject conf) {
        JSONArray hostList = conf.getJSONArray("host_list");
        JSONObject host = hostList.getJSONObject(0);
        String wssUrl = String.format("wss://%s:%s/sub", host.getString("host"), host.getString("wss_port"));
        System.out.println(wssUrl);
        return URI.create(wssUrl);
    }

    /**
     * 生成认证包的字节数据。
     *
     * @param credential 认证凭据
     * @return 认证包字节数组
     * @throws IOException 序列化失败时抛出
     */
    public byte[] generateAuthPack(Credential credential) throws IOException {
        return pack(credential.getCredential().toString(), Opt.AUTH);
    }

    /**
     * 生成心跳包的字节数据（无内容体）。
     *
     * @return 心跳包字节数组
     * @throws IOException 序列化失败时抛出
     */
    public static byte[] generateHeartBeatPack() throws IOException {
        return pack(null, Opt.HEARTBEAT);
    }

    /**
     * 按弹幕协议格式打包数据帧。
     * <p>
     * 帧结构（共 16 字节头 + 内容体）：
     * <pre>
     * [0-3]   总长度（头 + 体）
     * [4-5]   头长度（固定 16）
     * [6-7]   协议版本（固定 1）
     * [8-11]  操作码
     * [12-15] 序列号（固定 1）
     * [16-..] JSON 内容体（仅认证包有）
     * </pre>
     * </p>
     *
     * @param jsonStr JSON 内容（心跳包传 null）
     * @param code    操作码
     * @return 打包后的字节数组
     * @throws IOException 序列化失败时抛出
     */
    public static byte[] pack(String jsonStr, short code) throws IOException {
        byte[] contentBytes = new byte[0];
        if (Opt.AUTH == code) {
            contentBytes = jsonStr.getBytes(StandardCharsets.UTF_8);
        }
        try (ByteArrayOutputStream data = new ByteArrayOutputStream();
             DataOutputStream stream = new DataOutputStream(data)) {
            stream.writeInt(contentBytes.length + 16); // 总长度
            stream.writeShort(16);                     // 头长度
            stream.writeShort(1);                      // 协议版本
            stream.writeInt(code);                     // 操作码
            stream.writeInt(1);                        // 序列号
            if (Opt.AUTH == code) {
                stream.writeBytes(jsonStr);            // 内容体
            }
            return data.toByteArray();
        }
    }

    /**
     * 递归解包弹幕数据帧。
     * <p>
     * 处理逻辑：
     * <ol>
     *   <li>读取 16 字节帧头</li>
     *   <li>若协议版本为 Brotli，解压后递归解包</li>
     *   <li>若操作码为 SEND_SMS_REPLY，解析 JSON 并处理弹幕</li>
     *   <li>若 ByteBuffer 中仍有剩余数据，继续解包下一帧</li>
     * </ol>
     * </p>
     *
     * @param byteBuffer 接收到的原始字节数据
     * @throws IOException 解压或解析失败时抛出
     */
    public void unpack(ByteBuffer byteBuffer) throws IOException {
        int packageLen = byteBuffer.getInt();
        short headLength = byteBuffer.getShort();
        short protVer = byteBuffer.getShort();
        int optCode = byteBuffer.getInt();
        byteBuffer.getInt(); // 跳过序列号

        if (Opt.HEARTBEAT_REPLY == optCode) {
            System.out.println("这是服务端心跳回包");
        }

        // 读取内容体
        byte[] contentBytes = new byte[packageLen - headLength];
        byteBuffer.get(contentBytes);

        // 协议版本 3 = Brotli 压缩，需先解压再递归解包
        if (Version.BROTLI == protVer) {
            unpack(ByteBuffer.wrap(decompressBrotli(contentBytes)));
            return;
        }

        String content = new String(contentBytes, StandardCharsets.UTF_8);
        if (Opt.AUTH_REPLY == optCode) {
            System.out.println("这是认证回包");
        }

        // 弹幕/通知消息处理
        if (Opt.SEND_SMS_REPLY == optCode) {
            JSONObject jsonObject = JSONObject.parseObject(content);
            Object cmdObj = jsonObject.get("cmd");
            String cmd = cmdObj != null ? cmdObj.toString() : "";
            // B站部分版本 cmd 为 DANMU_MSG:4:1:1:1 等格式，需用 startsWith 匹配
            if (cmd.startsWith("DANMU_MSG")) {
                JSONArray infoArray = jsonObject.getJSONArray("info");
                String danmuContent = extractDanmuPlainText(infoArray);

                // info[2] 为用户信息数组，[1] 为用户名
                JSONArray userInfo = infoArray.getJSONArray(2);
                String username = userInfo != null && userInfo.size() > 1 ? userInfo.getString(1) : "匿名";

                // 追加到聊天缓冲区
                LiveChatBuffer.getInstance().append(username, danmuContent);

                System.out.println("用户: " + username);
                System.out.println("弹幕内容: " + danmuContent);

                // 尝试作为点歌指令处理
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

        // 同一个 ByteBuffer 中可能拼接了多个帧，继续解包
        if (byteBuffer.position() < byteBuffer.limit()) {
            unpack(byteBuffer);
        }
    }

    /**
     * 弹幕协议操作码常量。
     */
    static final class Opt {
        /** 客户端心跳 */
        static final short HEARTBEAT = 2;
        /** 服务端心跳回复 */
        static final short HEARTBEAT_REPLY = 3;
        /** 弹幕/通知消息 */
        static final short SEND_SMS_REPLY = 5;
        /** 客户端认证 */
        static final short AUTH = 7;
        /** 服务端认证回复 */
        static final short AUTH_REPLY = 8;
        private Opt() {}
    }

    /**
     * 弹幕协议版本号常量。
     */
    static final class Version {
        /** Brotli 压缩（协议版本 3） */
        static final short BROTLI = 3;
        private Version() {}
    }

    /**
     * 解压 Brotli 格式的字节数据。
     *
     * @param data Brotli 压缩的字节数组
     * @return 解压后的原始字节数组
     * @throws IOException 解压失败时抛出
     */
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
