package com.gitolk;

/**
 * 点歌请求的处理结果状态枚举。
 * <p>
 * 每个枚举值携带一条中文提示消息，可直接用于向用户反馈点歌结果。
 * </p>
 */
public enum SongRequestStatus {

    /** 歌单已达容量上限 */
    PLAYLIST_FULL("歌单已满，无法添加新歌"),

    /** 该谱面已在当前队列或正在播放中 */
    ALREADY_PLAYED("该歌曲已播放过，不能重复点播"),

    /** 同一用户在时间窗口内点歌次数过多 */
    TOO_FREQUENT("点歌过于频繁，请稍后再试"),

    /** 点歌成功 */
    SUCCESS("点歌成功");

    /** 人类可读的状态消息 */
    private final String message;

    SongRequestStatus(String message) {
        this.message = message;
    }

    /**
     * 获取该状态对应的中文提示文本。
     */
    public String getMessage() {
        return message;
    }
}
