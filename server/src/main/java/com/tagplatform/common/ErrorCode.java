package com.tagplatform.common;

/**
 * 全平台错误码（契约冻结，永不删除；分段设计）：
 *   0     成功
 *   1xxx  鉴权与签名        → HTTP 401/403
 *   2xxx  参数校验          → HTTP 200（业务级拒绝，code 表达）
 *   3xxx  业务规则/频控      → HTTP 200，频控类 429
 *   4xxx  控制台会话与权限   → HTTP 401/403
 *   5xxx  系统与存储         → HTTP 500
 */
public enum ErrorCode {

    OK(0, "ok", 200),

    // ---- 1xxx 鉴权与签名 ----
    INVALID_AUTH_HEADER(1001, "缺少或非法的签名请求头（X-App-Key/X-Timestamp/X-Nonce/X-Signature）", 401),
    APP_KEY_NOT_FOUND(1002, "AppKey 不存在", 401),
    SIGNATURE_MISMATCH(1003, "签名校验失败", 401),
    TIMESTAMP_EXPIRED(1004, "请求时间戳超出允许偏差（±300 秒），请校准时间", 401),
    NONCE_REPLAY(1005, "Nonce 重复，疑似重放请求", 401),
    TENANT_MISMATCH(1006, "请求中的 tenant_id 与凭证归属不一致", 403),
    TENANT_DISABLED(1007, "租户已被停用", 403),
    CREDENTIAL_DISABLED(1008, "AppKey 已被禁用", 401),
    CONSOLE_UNAUTHORIZED(1009, "控制台登录态无效或已过期", 401),

    // ---- 2xxx 参数校验 ----
    PARAM_MISSING(2001, "缺少必填参数", 200),
    PARAM_INVALID(2002, "参数校验失败", 200),
    BATCH_EXCEED_LIMIT(2003, "批量条数超出上限", 200),

    // ---- 3xxx 业务与频控 ----
    TAG_NOT_FOUND(3001, "标签不存在", 200),
    TAG_NOT_ONLINE(3002, "标签未上线或不可写", 200),
    OBJECT_TYPE_NOT_REGISTERED(3003, "对象类型未注册", 200),
    REQUEST_PROCESSING(3004, "相同 biz_request_id 的请求正在处理中，请稍后重试", 200),
    OPERATOR_FREQUENCY(3005, "操作员写入频率超限", 429),
    OBJECT_FREQUENCY(3006, "同对象同标签写入过于频繁", 429),
    IP_FREQUENCY(3007, "IP 请求频率超限", 429),
    REMARK_REQUIRED(3008, "敏感标签必须填写打标理由 remark", 200),
    TAG_VALUE_NOT_FOUND(3009, "标签值不存在", 200),
    NOT_PENDING_AUDIT(3010, "该标签值不在待审核状态", 200),
    TAG_OBJECT_TYPE_MISMATCH(3011, "标签不适用于该对象类型", 200),
    TAG_WRITE_DISABLED(3012, "标签不允许写入（写入开关已关闭）", 200),
    AUDIT_DISABLED(3013, "标签值审核已停用", 200),

    // ---- 4xxx 控制台会话与权限 ----
    CONSOLE_BAD_CREDENTIALS(4002, "用户名或密码错误", 401),
    CONSOLE_FORBIDDEN(4003, "当前角色无此操作权限", 403),
    CONSOLE_USER_DISABLED(4004, "控制台用户已停用", 401),
    CONSOLE_USER_EXISTS(4005, "控制台用户已存在", 200),
    CONSOLE_USER_NOT_FOUND(4006, "控制台用户不存在", 200),
    CONSOLE_LAST_ADMIN(4007, "至少需要保留一个启用的管理员账号", 200),
    CONSOLE_PASSWORD_POLICY(4008, "密码需为 8-64 位，且同时包含字母和数字", 200),
    CONSOLE_SELF_FORBIDDEN(4009, "不能对当前登录账号执行该操作", 200),
    CONSOLE_OLD_PASSWORD_MISMATCH(4010, "当前密码不正确", 200),

    // ---- 5xxx 系统与存储 ----
    SYSTEM_ERROR(5001, "系统内部错误", 500),
    STORAGE_ERROR(5002, "存储写入失败，请稍后重试", 500);

    private final int code;
    private final String message;
    private final int httpStatus;

    ErrorCode(int code, String message, int httpStatus) {
        this.code = code;
        this.message = message;
        this.httpStatus = httpStatus;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }

    public int httpStatus() {
        return httpStatus;
    }
}
