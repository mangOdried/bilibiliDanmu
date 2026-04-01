package pojo;

import com.alibaba.fastjson2.JSONObject;

public class Credential {
    //信息认证类
    private final JSONObject credential =new JSONObject();
    public Credential(Integer  uid, Integer  roomId, String buvid, String key){
        credential.put("uid", uid);
        credential.put("roomid",roomId);
        credential.put("protover", 3);
        credential.put("platform", "web");
        credential.put("type", 2);
        credential.put("buvid", buvid);
        credential.put("key", key);
    }
    public JSONObject getCredential(){
        return credential;
    }
}
