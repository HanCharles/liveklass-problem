package com.example.notification.common;

public record ApiResponse<T>(boolean success, T data, String errorCode, String message) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, null, null);
    }

    public static <T> ApiResponse<T> error(ErrorCode errorCode, String message) {
        return new ApiResponse<>(false, null, errorCode.name(), message);
    }
}
