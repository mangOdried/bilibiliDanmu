package org.example;

import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class WbiKeyManager {
    private static String imgKey = null;
    private static String subKey = null;

    public static void refreshWbiKeys() {
        try {
            URL url = new URL("https://api.bilibili.com/x/web-interface/nav");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");

            BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
            String result = reader.lines().reduce("", (a, b) -> a + b);
            reader.close();

            JSONObject json = JSONObject.parseObject(result);
            JSONObject data = json.getJSONObject("data");
            JSONObject wbiImg = data.getJSONObject("wbi_img");

            String imgUrl = wbiImg.getString("img_url");
            String subUrl = wbiImg.getString("sub_url");

            imgKey = extractKey(imgUrl);
            subKey = extractKey(subUrl);

        } catch (Exception e) {
            throw new RuntimeException("WBI密钥获取失败", e);
        }
    }

    private static String extractKey(String url) {
        Pattern pattern = Pattern.compile("/([a-zA-Z0-9]+)\\.png");
        Matcher matcher = pattern.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("未找到 wbi 密钥");
    }

    public static String getImgKey() {
        if (imgKey == null) refreshWbiKeys();
        return imgKey;
    }

    public static String getSubKey() {
        if (subKey == null) refreshWbiKeys();
        return subKey;
    }

}

