package com.tagplatform.console.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.console.RolePermissionCatalog;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台 API · 内置角色与权限矩阵（只读）。
 */
@RestController
@RequirePermission("page.roles")
@RequestMapping({"/api/v1/console/roles", "/console/api/roles"})
public class ConsoleRoleController {

    @GetMapping("/permissions")
    public ApiResponse<RolePermissionCatalog.Catalog> permissions() {
        ConsolePermission.requireAdminOrTenantAdmin();
        return ApiResponse.ok(RolePermissionCatalog.catalog());
    }
}
