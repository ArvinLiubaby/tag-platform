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

    /** 非平台角色访问带 tenantId 的查询时，租户管理员强制限定为本租户 */
    public static String scopeTenant(String requestedTenantId) {
        requireAny();
        if (!"TENANT_ADMIN".equals(RequestContext.getConsoleRole())) {
            return requestedTenantId;
        }
        String own = RequestContext.getConsoleTenantId();
        if (own == null || own.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        if (requestedTenantId != null && !requestedTenantId.isBlank() && !own.equals(requestedTenantId)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "不能访问其他租户数据");
        }
        return own;
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
