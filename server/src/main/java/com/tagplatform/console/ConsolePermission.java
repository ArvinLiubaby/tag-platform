package com.tagplatform.console;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;

/**
 * 控制台 RBAC 简易权限检查（ADMIN / AUDITOR / VIEWER）。
 */
public final class ConsolePermission {

    private ConsolePermission() {
    }

    /** 任意已登录角色 */
    public static void requireAny() {
        if (RequestContext.getConsoleRole() == null) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
    }

    public static void requireAdmin() {
        requireAny();
        if (!"ADMIN".equals(RequestContext.getConsoleRole())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
    }

    /** 平台管理员或租户管理员（具体数据范围由业务服务继续校验） */
    public static void requireAdminOrTenantAdmin() {
        requireAny();
        String role = RequestContext.getConsoleRole();
        if (!"ADMIN".equals(role) && !"TENANT_ADMIN".equals(role)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
    }

    /**
     * 控制台数据接口的租户范围：
     *   ADMIN 可显式指定租户，未指定表示全租户；
     *   其余角色（TENANT_ADMIN / TASK_MANAGER / TAGGER / AUDITOR / VIEWER）一律锁定账号绑定租户，
     *   传入其他租户直接拒绝，避免只靠前端传参造成越权。
     */
    public static String scopeTenant(String requestedTenantId) {
        requireAny();
        String requested = trimToNull(requestedTenantId);
        if ("ADMIN".equals(RequestContext.getConsoleRole())) {
            return requested;
        }
        String own = trimToNull(RequestContext.getConsoleTenantId());
        if (own == null) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        if (requested != null && !own.equals(requested)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "不能访问其他租户数据");
        }
        return own;
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public static void requireRole(String... roles) {
        requireAny();
        String current = RequestContext.getConsoleRole();
        for (String role : roles) {
            if (role.equals(current)) {
                return;
            }
        }
        throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
    }
}
