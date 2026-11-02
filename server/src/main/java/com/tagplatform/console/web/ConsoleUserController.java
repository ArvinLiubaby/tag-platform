package com.tagplatform.console.web;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.console.ConsoleUserService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台 API · 用户管理（平台管理员 / 租户管理员）：列表、新建、启停、角色调整、重置密码。
 * TENANT_ADMIN 只能看到并管理本租户的普通角色账号，平台管理员可管理全部。
 */
@RestController
@RequirePermission("page.users")
@RequestMapping({"/api/v1/console/users", "/console/api/users"})
public class ConsoleUserController {

    private final ConsoleUserService userService;
    private final AuditLogService auditLogService;

    public ConsoleUserController(ConsoleUserService userService, AuditLogService auditLogService) {
        this.userService = userService;
        this.auditLogService = auditLogService;
    }

    @GetMapping
    public ApiResponse<List<ConsoleUserService.UserView>> list() {
        ConsolePermission.requireAdminOrTenantAdmin();
        return ApiResponse.ok(userService.list());
    }

    @RequirePermission("op.user.manage")
    @PostMapping
    public ApiResponse<ConsoleUserService.UserView> create(@RequestBody CreateBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        ConsoleUserService.UserView created = userService.create(body.username(), body.displayName(),
                body.password(), body.role(), body.tenantId(), body.operatorId(), body.operatorName(),
                body.operatorOrg(), body.team());
        auditLogService.record("USER_CREATE", null, null, null, null,
                "新建控制台用户: " + created.username() + "（" + created.role() + "）", "success");
        return ApiResponse.ok(created);
    }

    @RequirePermission("op.user.manage")
    @PostMapping("/{username}/status")
    public ApiResponse<ConsoleUserService.UserView> changeStatus(@PathVariable("username") String username,
                                                                 @RequestBody StatusBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        ConsoleUserService.UserView updated = userService.changeStatus(
                RequestContext.getConsoleUsername(), username, body.status());
        auditLogService.record("USER_STATUS_CHANGE", null, null, null, null,
                "用户状态变更为 " + body.status() + ": " + updated.username(), "success");
        return ApiResponse.ok(updated);
    }

    @RequirePermission("op.user.manage")
    @PostMapping("/{username}/role")
    public ApiResponse<ConsoleUserService.UserView> changeRole(@PathVariable("username") String username,
                                                               @RequestBody RoleBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        ConsoleUserService.UserView updated = userService.changeRole(
                RequestContext.getConsoleUsername(), username, body.role());
        auditLogService.record("USER_ROLE_CHANGE", null, null, null, null,
                "用户角色变更为 " + updated.role() + ": " + updated.username(), "success");
        return ApiResponse.ok(updated);
    }

    @RequirePermission("op.user.manage")
    @PostMapping("/{username}/password")
    public ApiResponse<Void> resetPassword(@PathVariable("username") String username,
                                           @RequestBody ResetPasswordBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        userService.resetPassword(RequestContext.getConsoleUsername(), username, body.newPassword());
        auditLogService.record("USER_PASSWORD_RESET", null, null, null, null,
                "重置用户密码: " + username + "（该用户会话已失效）", "success");
        return ApiResponse.ok(null);
    }

    public record CreateBody(String username, String displayName, String password, String role,
                             String tenantId, String operatorId, String operatorName,
                             String operatorOrg, String team) {
    }

    public record StatusBody(String status) {
    }

    public record RoleBody(String role) {
    }

    public record ResetPasswordBody(String newPassword) {
    }
}
