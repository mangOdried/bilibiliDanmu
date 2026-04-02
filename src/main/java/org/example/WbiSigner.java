package org.example;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * B站 WBI 签名生成器。
 * <p>
 * B站部分 API（如弹幕信息接口）要求请求参数携带 WBI 签名（w_rid）,
 * 签名算法为：将参数按 key 升序排列拼接成查询串，追加 mixinKey 后取 MD5。
 * </p>
 * <p>
 * mixinKey 由 imgKey 和 subKey 拼接后，按 {@link #MIXIN_KEY_ENC_TAB} 重排前 32 位字符得到。
 * </p>
 *
 * @see WbiKeyManager 负责从 B站 nav 接口获取 imgKey / subKey
 */
public class WbiSigner {

    /**
     * 混淆密钥重排表：从 imgKey + subKey 共 64 字符中，
     * 按此表指定的下标选取 32 个字符作为最终 mixinKey。
     */
    private static final int[] MIXIN_KEY_ENC_TAB = new int[]{
            46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35, 27, 43, 5, 49,
            33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13, 37, 48, 7, 16, 24, 55, 40,
            61, 26, 17, 0, 1, 60, 51, 30, 4, 22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11,
            36, 20, 34, 44, 52
    };

    /** 十六进制字符映射表，用于 MD5 结果转字符串 */
    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private WbiSigner() {}

    /**
     * 计算字符串的 MD5 摘要（32 位小写十六进制）。
     *
     * @param input 输入字符串
     * @return 32 字符的 MD5 十六进制串
     * @throws RuntimeException 当 JVM 不支持 MD5 时抛出（理论上不会发生）
     */
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

    /**
     * 将 imgKey + subKey 拼接后，按重排表 {@link #MIXIN_KEY_ENC_TAB} 取前 32 位字符生成 mixinKey。
     *
     * @param imgKey B站 wbi_img 的 img_url 中提取的 key
     * @param subKey B站 wbi_img 的 sub_url 中提取的 key
     * @return 32 字符的 mixinKey
     */
    static String getMixinKey(String imgKey, String subKey) {
        String s = imgKey + subKey;
        StringBuilder key = new StringBuilder(32);
        for (int i = 0; i < 32; i++) {
            key.append(s.charAt(MIXIN_KEY_ENC_TAB[i]));
        }
        return key.toString();
    }

    /**
     * 对对象值进行 URL 编码（空格编码为 %20 而非 +）。
     *
     * @param o 要编码的值
     * @return URL 编码后的字符串
     */
    static String encodeURIComponent(Object o) {
        return URLEncoder.encode(o.toString(), StandardCharsets.UTF_8).replace("+", "%20");
    }

    /**
     * 为指定的房间/接口 ID 和类型生成带 WBI 签名的查询参数串。
     * <p>
     * 返回格式类似：{@code id=xxx&type=xxx&web_location=444.8&w_rid=xxx}
     * </p>
     *
     * @param id   请求目标 ID（如房间号）
     * @param type 请求类型（0 表示默认）
     * @return 签名后的完整查询参数串
     */
    public static String generateWbi(int id, int type) {
        String imgKey = WbiKeyManager.getImgKey();
        String subKey = WbiKeyManager.getSubKey();
        String mixinKey = getMixinKey(imgKey, subKey);

        // TreeMap 保证参数按 key 字典序排列
        TreeMap<String, Object> map = new TreeMap<>();
        map.put("id", id);
        map.put("type", type);
        map.put("web_location", "444.8");

        String param = map.entrySet().stream()
                .map(it -> String.format("%s=%s", it.getKey(), encodeURIComponent(it.getValue())))
                .collect(Collectors.joining("&"));

        // 签名 = md5(参数串 + mixinKey)
        String wbiSign = md5(param + mixinKey);
        return param + "&w_rid=" + wbiSign;
    }
}
