package com.gitolk;

public class SongRequestResult {
    private final boolean success;
    private final SongRequestStatus errorType;

    public SongRequestResult(boolean success, SongRequestStatus errorType) {
        this.success = success;
        this.errorType = errorType;
    }

    public boolean isSuccess() {
        return success;
    }

    public SongRequestStatus getErrorType() {
        return errorType;
    }

    public String getMessage() {
        return errorType.getMessage();
    }


}

