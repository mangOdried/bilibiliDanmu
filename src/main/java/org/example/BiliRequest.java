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

public class BiliRequest {
    private final String cookie;

    public BiliRequest(String cookie) {
        this.cookie = cookie;
    }

    /**
     * 获取直播间ID ，因为存在短ID
     */
    public String getReadRoomId(int roomid) throws IOException {
        return get("https://api.live.bilibili.com/room/v1/Room/room_init?id=" + roomid);
    }

    public String getDanmuInfo(int roomid) throws IOException {
        String url = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?" + WbiSigner.generateWbi(roomid, 0);
        return get(url);
    }

    public JSONObject getDanmuInfoData(int roomid) throws IOException {
        JSONObject readRoomId = JSONObject.parseObject(getReadRoomId(roomid));
        roomid = readRoomId.getJSONObject("data").getIntValue("room_id");
        JSONObject data = JSONObject.parseObject(getDanmuInfo(roomid)).getJSONObject("data");
        data.put("room_id", roomid);
        return data;
    }

    enum Method {
        GET("GET"), POST("POST");
        public final String code;

        Method(String code) {
            this.code = code;
        }
    }

    public String get(String url) throws IOException {
        return request(Method.GET, url, null);
    }

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
