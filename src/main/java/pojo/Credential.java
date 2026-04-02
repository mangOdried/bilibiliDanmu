package pojo;

import com.alibaba.fastjson2.JSONObject;

/**
 * B站直播弹幕 WebSocket 认证凭据。
 * <p>
 * 连接弹幕 WebSocket 时需要发送一个 JSON 认证包，
 * 本类负责将 uid、roomId、buvid、token 等参数组装为认证所需的 JSON 结构。
 * </p>
 */
public class Credential {

    /** 认证 JSON 载体，包含连接弹幕服务器所需的全部字段 */
    private final JSONObject credential = new JSONObject();

    /**
     * 构造认证凭据。
     *
     * @param uid    B站用户 UID
     * @param roomId 直播间真实房间号（长号）
     * @param buvid  浏览器 buvid3 标识串
     * @param key    弹幕服务 token（来自 getDanmuInfo 接口）
     */
    public Credential(Integer uid, Integer roomId, String buvid, String key) {
        credential.put("uid", uid);
        credential.put("roomid", roomId);
        credential.put("protover", 3);     // 协议版本 3 表示 Brotli 压缩
        credential.put("platform", "web");
        credential.put("type", 2);
        credential.put("buvid", buvid);
        credential.put("key", key);
    }

    /**
     * 获取完整的认证 JSON 对象，用于序列化后写入 WebSocket 认证包。
     */
    public JSONObject getCredential() {
        return credential;
    }
}
