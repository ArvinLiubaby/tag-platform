package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;

/**
 * 工作台/任务的租户边界与角色检查：
 *   ADMIN 可显式切换租户；TENANT_ADMIN / TAGGER / TASK_MANAGER / AUDITOR / VIEWER 固定使用会话绑定的主租户。
 */
public final class WorkbenchAccess {

    private WorkbenchAccess() {
    }

    public static String resolveTenant(String requestedTenantId) {
        String role = RequestContext.getConsoleRole();
        if (role == null) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
        String bound = RequestContext.getConsoleTenantId();
        String requested = trimToNull(requestedTenantId);
        if ("ADMIN".equals(role)) {
            String tenant = requested != null ? requested : trimToNull(bound);
            if (tenant == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "管理员需指定 tenant_id");
            }
            return tenant;
        }
        if (bound == null) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户，无法使用工作台");
        }
        if (requested != null && !requested.equals(bound)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "不能跨租户访问: " + requested);
        }
        return bound;
    }

    public static String requireTenant() {
        return resolveTenant(null);
    }

    public static void requireWorkbench() {
        requireRole("ADMIN", "TENANT_ADMIN", "TAGGER", "TASK_MANAGER", "AUDITOR", "VIEWER");
    }

    public static void requireTagging() {
        requireRole("ADMIN", "TENANT_ADMIN", "TAGGER", "TASK_MANAGER");
    }

    public static void requireTaskManager() {
        requireRole("ADMIN", "TENANT_ADMIN", "TASK_MANAGER");
    }

    public static void requireReviewer() {
        requireRole("ADMIN", "TENANT_ADMIN", "AUDITOR", "TASK_MANAGER");
    }

    public static void requireRole(String... roles) {
        String current = RequestContext.getConsoleRole();
        if (current == null) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
        for (String role : roles) {
            if (role.equals(current)) {
                return;
            }
        }
        throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
    }

    public static String currentUsername() {
        String username = RequestContext.getConsoleUsername();
        if (username == null || username.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
        return username;
    }

    public static String displayName() {
        String name = RequestContext.getConsoleDisplayName();
        return name == null || name.isBlank() ? currentUsername() : name;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
