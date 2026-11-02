package com.tagplatform.console.web;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsoleApiAuthFilter;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.ConsoleSessionService;
import com.tagplatform.console.ConsoleUserService;
import com.tagplatform.console.RolePermissionCatalog;
import com.tagplatform.console.security.PublicConsoleEndpoint;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台 API · 登录 / 登出 / 当前用户。
 */
@RestController
@PublicConsoleEndpoint
@RequestMapping({"/api/v1/console/auth", "/console/api/auth"})
public class ConsoleAuthController {

    private final ConsoleSessionService sessions;
    private final ConsoleUserService userService;
    private final AuditLogService auditLogService;

    public ConsoleAuthController(ConsoleSessionService sessions, ConsoleUserService userService,
                                 AuditLogService auditLogService) {
        this.sessions = sessions;
        this.userService = userService;
        this.auditLogService = auditLogService;
    }

    @PostMapping("/login")
    public ApiResponse<ConsoleSessionService.LoginResult> login(@RequestBody LoginBody body) {
        return ApiResponse.ok(sessions.login(body.username(), body.password()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        sessions.logout(ConsoleApiAuthFilter.extractToken(request));
        return ApiResponse.ok(null);
    }

    /** 本人修改密码：校验旧密码，成功后当前 Token 立即失效，需用新密码重新登录 */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(@RequestBody ChangePasswordBody body) {
        ConsolePermission.requireAny();
        String username = RequestContext.getConsoleUsername();
        userService.changeOwnPassword(username, body.oldPassword(), body.newPassword());
        auditLogService.record("USER_PASSWORD_CHANGE", null, null, null, null,
                "修改本人登录密码（会话已失效）", "success");
        return ApiResponse.ok(null);
    }

    @GetMapping("/permissions")
    public ApiResponse<PermissionView> permissions() {
        String role = RequestContext.getConsoleRole();
        return ApiResponse.ok(new PermissionView(role,
                RolePermissionCatalog.permissionsForRole(role).stream().toList(),
                RolePermissionCatalog.menusForRole(role)));
    }

    @GetMapping("/me")
    public ApiResponse<MeView> me() {
        return ApiResponse.ok(new MeView(RequestContext.getConsoleUsername(),
                RequestContext.getConsoleDisplayName(), RequestContext.getConsoleRole(),
                RequestContext.getConsoleTenantId(), RequestContext.getConsoleOperatorId(),
                RequestContext.getConsoleOperatorName(), RequestContext.getConsoleOperatorOrg(),
                RequestContext.getConsoleTeam()));
    }

    public record LoginBody(String username, String password) {
    }

    public record ChangePasswordBody(String oldPassword, String newPassword) {
    }

    public record MeView(String username, String displayName, String role, String tenantId,
                         String operatorId, String operatorName, String operatorOrg, String team) {
    }

    public record PermissionView(String role, List<String> permissions, List<String> menus) {
    }
}
