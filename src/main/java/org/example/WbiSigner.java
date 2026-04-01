package org.example;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;
import java.util.stream.Collectors;

public class WbiSigner {
    private static final int[] MIXIN_KEY_ENC_TAB = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private WbiSigner() {}

    public static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            char[] result = new char[digest.length * 2];
            for (int i = 0; i < digest.length; i++) {
                result[i * 2] = HEX_DIGITS[(digest[i] >> 4) & 0xF];
                result[i * 2 + 1] = HEX_DIGITS[digest[i] & 0xF];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 algorithm not available", e);
        }
    }

    static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            key.append(s.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return key.toString();
    }

    static String encodeURIComponent(Object o) {
        return URLEncoder.encode(o.toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static String generateWbi(int id, int type) {
        String imgKey = WbiKeyManager.getImgKey();
        String subKey = WbiKeyManager.getSubKey();
        String mixinKey = getMixinKey(imgKey, subKey);

        TreeMap<String, Object> map = new TreeMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("web_location", "444.8");

        String param = map.entrySet().stream()
                .map(it -> String.format("%s=%s", it.getKey(), encodeURIComponent(it.getValue())))
                .collect(Collectors.joining("&"));

        String wbiSign = md5(param + mixinKey);
        return param + "&w_rid=" + wbiSign;
    }
}
