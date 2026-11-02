package com.tagplatform.console.security;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.RolePermissionCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 控制台统一权限拦截器：读取 @RequirePermission，按角色权限目录校验。
 * 没有权限声明且不是公共端点的控制台 HandlerMethod 默认拒绝，防止新增接口漏配权限。
 */
@Component
public class PermissionInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod method)) {
            return true;
        }
        if (hasAnnotation(method, PublicConsoleEndpoint.class)) {
            return true;
        }
        RequirePermission permission = method.getMethodAnnotation(RequirePermission.class);
        if (permission == null) {
            permission = method.getBeanType().getAnnotation(RequirePermission.class);
        }
        if (permission == null) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "接口未配置权限点");
        }
        String role = RequestContext.getConsoleRole();
        if (role == null) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED);
        }
        if (!RolePermissionCatalog.hasPermission(role, permission.value())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN,
                    "当前角色无权限执行该操作: " + permission.value());
        }
        return true;
    }

    private boolean hasAnnotation(HandlerMethod method, Class<? extends java.lang.annotation.Annotation> type) {
        return method.getMethodAnnotation(type) != null
                || method.getBeanType().isAnnotationPresent(type);
    }
}
