package org.example;

import com.alibaba.fastjson2.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

public class WbiKeyManager {
    private static final Pattern KEY_PATTERN = Pattern.compile("/([a-zA-Z0-9]+)\\.png");

    private static volatile String imgKey = null;
    private static volatile String subKey = null;

    public static void refreshWbiKeys() {
        try {
            HttpURLConnection conn = (HttpURLConnection) URI.create("https://api.bilibili.com/x/web-interface/nav").toURL().openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "Mozilla/5.0");
            conn.setRequestProperty("Accept", "application/json");

            String result;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                result = reader.lines().collect(Collectors.joining());
            }

            JSONObject json = JSONObject.parseObject(result);
            JSONObject data = json.getJSONObject("data");
            JSONObject wbiImg = data.getJSONObject("wbi_img");

            imgKey = extractKey(wbiImg.getString("img_url"));
            subKey = extractKey(wbiImg.getString("sub_url"));
        } catch (Exception e) {
            throw new RuntimeException("WBI密钥获取失败", e);
        }
    }

    private static String extractKey(String url) {
        Matcher matcher = KEY_PATTERN.matcher(url);
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

