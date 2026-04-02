package org.example;

import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * B站直播 API HTTP 请求工具类。
 * <p>
 * 封装了携带 Cookie 认证的 GET/POST 请求，
 * 主要用于获取直播间信息和弹幕连接数据。
 * </p>
 */
public class BiliRequest {

    /** B站浏览器 Cookie，用于接口鉴权 */
    private final String cookie;

    /**
     * @param cookie 从浏览器复制的完整 Cookie 字符串
     */
    public BiliRequest(String cookie) {
        this.cookie = cookie;
    }

    /**
     * 获取直播间真实房间号。
     * <p>
     * B站存在短 ID 和长 ID 之分，此接口将短 ID 解析为真实的 room_id。
     * </p>
     *
     * @param roomid 直播间号（可以是短号或长号）
     * @return room_init 接口的 JSON 响应字符串
     * @throws IOException 网络请求失败时抛出
     */
    public String getReadRoomId(int roomid) throws IOException {
        return get("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + roomid);
    }

    /**
     * 获取弹幕服务连接信息（WebSocket 地址与 token）。
     * <p>
     * 需要 WBI 签名参数，内部调用 {@link WbiSigner#generateWbi} 自动生成。
     * </p>
     *
     * @param roomid 直播间真实房间号
     * @return getDanmuInfo 接口的 JSON 响应字符串
     * @throws IOException 网络请求失败时抛出
     */
    public String getDanmuInfo(int roomid) throws IOException {
        String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + WbiSigner.generateWbi(roomid, 0);
        return get(url);
    }

    /**
     * 一站式获取弹幕服务所需的全部数据（真实房间号 + WebSocket 连接信息）。
     * <p>
     * 先调用 room_init 获取真实 room_id，再调用 getDanmuInfo 获取 host_list 和 token。
     * 返回的 JSONObject 额外注入了 room_id 字段，便于后续直接使用。
     * </p>
     *
     * @param roomid 直播间号（可以是短号）
     * @return 包含 host_list、token、room_id 等信息的 JSONObject
     * @throws IOException 网络请求失败时抛出
     */
    public JSONObject getDanmuInfoData(int roomid) throws IOException {
        JSONObject readRoomId = JSONObject.parseObject(getReadRoomId(roomid));
        roomid = readRoomId.getJSONObject("data").getIntValue("room_id");
        JSONObject data = JSONObject.parseObject(getDanmuInfo(roomid)).getJSONObject("data");
        data.put("room_id", roomid);
        return data;
    }

    /** HTTP 请求方法枚举 */
    enum Method {
        GET("GET"), POST("POST");

        /** HTTP 方法字符串 */
        public final String code;

        Method(String code) {
            this.code = code;
        }
    }

    /**
     * 发送 GET 请求。
     *
     * @param url 请求 URL
     * @return 响应体字符串
     * @throws IOException 网络请求失败时抛出
     */
    public String get(String url) throws IOException {
        return request(Method.GET, url, null);
    }

    /**
     * 通用 HTTP 请求方法。
     * <p>
     * 所有请求默认携带 User-Agent、Accept、Content-Type 和 Cookie 头。
     * POST 请求时将 dataMap 序列化为 JSON 写入请求体。
     * </p>
     *
     * @param method  HTTP 方法
     * @param url     请求 URL
     * @param dataMap POST 请求体数据（GET 时传 null）
     * @return 响应体字符串
     * @throws IOException 网络请求失败时抛出
     */
    private String request(Method method, String url, Map<String, Object> dataMap) throws IOException {
        HttpURLConnection con = (HttpURLConnection) URI.create(url).toURL().openConnection();
        con.setRequestMethod(method.code);
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Cookie", cookie);
        if (Method.POST == method && dataMap != null && !dataMap.isEmpty()) {
            String bodyStr = JSONObject.toJSONString(dataMap);
            con.setDoOutput(true);
            try (DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
                wr.writeBytes(bodyStr);
                wr.flush();
            }
        }
        try (BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))) {
            return bufferedReader.lines().collect(Collectors.joining("\n"));
        }
    }
}
