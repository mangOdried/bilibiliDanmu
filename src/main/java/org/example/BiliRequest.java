package org.example;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import jakarta.websocket.ContainerProvider;
import jakarta.websocket.DeploymentException;
import jakarta.websocket.WebSocketContainer;
import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.stream.Collectors;

public class BiliRequest {
    private String cookie;


    public BiliRequest(String cookie) {
        this.cookie = cookie;
    }

    /**
     * 获取直播间ID ，因为存在短ID
     */
    public String getReadRoomId(int roomid) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        return get("https://api.live.bilibili.com/room/v1/Room/room_init?id="+roomid);
    }
    /**获得弹幕服务地址信息**/
    public String getDanmuInfo(int roomid) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        String url ="https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo?"+WbiTest.generateWbi(roomid,0);
        return get(url);
    }

    public JSONObject getDanmuInfoData(int roomid) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        JSONObject readRoomId = JSONObject.parseObject(getReadRoomId(roomid));
        roomid = readRoomId.getJSONObject("data").getIntValue("room_id");
        JSONObject data = JSONObject.parseObject(getDanmuInfo(roomid)).getJSONObject("data");
        data.put("room_id", roomid);
        return data;

    }

    enum Method{
        GET("GET"),POST("POST");
        public String code;

        Method(String code) {
            this.code = code;
        }
    }
    public String get(String url) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        return request(Method.GET,url,null);
    }

    private String request(Method method,String url, Map<String,Object> dataMap) throws IOException, NoSuchAlgorithmException, InvalidKeyException {
        String bodyStr = JSONObject.toJSONString(dataMap);
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod(method.code);
        /**----------设置请求头------------------------------------------------------------------**/
        con.setRequestProperty("User-Agent", "Mozilla/5.0");
        con.setRequestProperty("Accept", "application/json");
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Cookie", cookie);
        // 发送 POST 请求
        if(Method.POST == method && null != dataMap && !dataMap.isEmpty()){
            con.setDoOutput(true);
            try(DataOutputStream wr = new DataOutputStream(con.getOutputStream())) {
                wr.writeBytes(bodyStr);
                wr.flush();
            }
        }
        // 获取响应结果
        try(BufferedReader bufferedReader = new BufferedReader(new InputStreamReader(con.getInputStream(), StandardCharsets.UTF_8))){
            // 返回响应结果
            String respons = bufferedReader.lines().collect(Collectors.joining("\n"));
            System.out.println(respons);
            return respons ;
        }
    }
}






