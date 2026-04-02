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

/**
 * B站 WBI 密钥管理器：从 B站 nav 接口获取并缓存 imgKey / subKey。
 * <p>
 * WBI 签名需要 imgKey 和 subKey 两个密钥，它们通过
 * {@code https://api.bilibili.com/x/web-interface/nav} 接口
 * 的 wbi_img 字段获得。密钥嵌入在 PNG URL 路径中（如 {@code /xxx.png}）。
 * </p>
 * <p>
 * 首次调用 {@link #getImgKey()} 或 {@link #getSubKey()} 时自动触发 HTTP 请求获取密钥，
 * 之后缓存在静态字段中供后续复用。
 * </p>
 */
public class WbiKeyManager {

    /** 从 PNG URL 路径中提取密钥的预编译正则：匹配 /xxxxx.png 中的 xxxxx */
    private static final Pattern KEY_PATTERN = Pattern.compile("/([a-zA-Z0-9]+)\\.png");

    /** 缓存的 img 密钥（volatile 保证多线程可见性） */
    private static volatile String imgKey = null;

    /** 缓存的 sub 密钥 */
    private static volatile String subKey = null;

    /**
     * 从 B站 nav 接口刷新 WBI 密钥。
     * <p>
     * 请求 nav 接口 → 解析 JSON → 从 wbi_img 中提取 img_url / sub_url → 正则提取密钥。
     * </p>
     *
     * @throws RuntimeException 请求失败或解析失败时抛出
     */
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

    /**
     * 从 PNG 文件 URL 中提取密钥字符串。
     * <p>
     * 例如 {@code https://i0.hdslb.com/bfs/wbi/abc123.png} → {@code abc123}
     * </p>
     *
     * @param url PNG 文件的完整 URL
     * @return 提取出的密钥
     * @throws RuntimeException URL 格式不匹配时抛出
     */
    private static String extractKey(String url) {
        Matcher matcher = KEY_PATTERN.matcher(url);
        if (matcher.find()) {
            return matcher.group(1);
        }
        throw new RuntimeException("未找到 wbi 密钥");
    }

    /**
     * 获取 img 密钥（首次调用时自动刷新）。
     */
    public static String getImgKey() {
        if (imgKey == null) refreshWbiKeys();
        return imgKey;
    }

    /**
     * 获取 sub 密钥（首次调用时自动刷新）。
     */
    public static String getSubKey() {
        if (subKey == null) refreshWbiKeys();
        return subKey;
    }
}
