package com.gitolk;

/**
 * 点歌错误状态
 */
public enum SongRequestStatus {
    PLAYLIST_FULL("歌单已满，无法添加新歌"),
    ALREADY_PLAYED("该歌曲已播放过，不能重复点播"),
    TOO_FREQUENT("点歌过于频繁，请稍后再试"),
    SUCCESS("点歌成功");

    private final String message;

    SongRequestStatus(String message) {
        this.message = message;
    }

    public String getMessage() {
        return message;
    }
}
