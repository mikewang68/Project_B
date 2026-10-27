package com.mt.wms.common.api;

public record ApiResponse<T>(String code, String message, String requestId, T data) {
    public static <T> ApiResponse<T> ok(T data, String requestId) {
        return new ApiResponse<>("OK", "成功", requestId, data);
    }

    public static ApiResponse<Void> error(String code, String message, String requestId) {
        return new ApiResponse<>(code, message, requestId, null);
    }
}

