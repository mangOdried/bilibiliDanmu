package com.gitolk;

/**
 * 点歌请求的处理结果，封装成功/失败标志和对应的状态枚举。
 * <p>
 * 调用方可通过 {@link #isSuccess()} 判断是否点歌成功，
 * 若失败则通过 {@link #getMessage()} 获取失败原因的中文描述。
 * </p>
 */
public class SongRequestResult {

    /** 是否成功 */
    private final boolean success;

    /** 对应的状态枚举（包含成功和各种失败原因） */
    private final SongRequestStatus errorType;

    /**
     * 构造点歌结果。
     *
     * @param success   是否成功
     * @param errorType 状态枚举
     */
    public SongRequestResult(boolean success, SongRequestStatus errorType) {
        this.success = success;
        this.errorType = errorType;
    }

    /** 点歌是否成功 */
    public boolean isSuccess() {
        return success;
    }

    /** 获取状态枚举 */
    public SongRequestStatus getErrorType() {
        return errorType;
    }

    /** 获取中文提示消息（由 {@link SongRequestStatus#getMessage()} 提供） */
    public String getMessage() {
        return errorType.getMessage();
    }
}
