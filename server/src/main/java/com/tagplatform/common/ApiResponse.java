package com.tagplatform.common;

/**
 * 统一响应结构：{code, message, request_id, data}
 * code = 0 表示成功；非 0 见 {@link ErrorCode}。
 */
public record ApiResponse<T>(int code, String message, String requestId, T data) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(0, "ok", RequestContext.getRequestId(), data);
    }

    public static <T> ApiResponse<T> of(int code, String message, T data) {
        return new ApiResponse<>(code, message, RequestContext.getRequestId(), data);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode) {
        return fail(errorCode, null);
    }

    public static <T> ApiResponse<T> fail(ErrorCode errorCode, T data) {
        return new ApiResponse<>(errorCode.code(), errorCode.message(), RequestContext.getRequestId(), data);
    }
}
