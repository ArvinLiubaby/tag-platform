package com.tagplatform.sdk;

/**
 * SDK 统一异常：
 *   - 服务端业务错误：携带 code / requestId（见平台错误码表）
 *   - 网络/解析错误：code = -1，message 描述原因
 */
public class TagPlatformException extends RuntimeException {

    private final int code;
    private final String requestId;

    public TagPlatformException(int code, String message, String requestId) {
        super(message);
        this.code = code;
        this.requestId = requestId;
    }

    public TagPlatformException(int code, String message, String requestId, Throwable cause) {
        super(message, cause);
        this.code = code;
        this.requestId = requestId;
    }

    public int getCode() {
        return code;
    }

    public String getRequestId() {
        return requestId;
    }

    /** 是否为可重试错误（网络异常 / 5xx / 429 频控） */
    public boolean retryable() {
        return code == -1 || code == 5001 || code == 5002
                || code == 3005 || code == 3006 || code == 3007;
    }
}
