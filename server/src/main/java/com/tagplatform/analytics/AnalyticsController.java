package com.tagplatform.analytics;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台数据看板接口（仅登录用户可访问）。
 */
@RestController
@RequirePermission("page.dashboard")
@RequestMapping({"/api/v1/console", "/console/api"})
public class AnalyticsController {

    private final AnalyticsService analyticsService;

    public AnalyticsController(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @GetMapping("/dashboard/overview")
    public ApiResponse<DashboardOverview> overview(
            @RequestParam(value = "days", defaultValue = "7") int days,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        // 平台管理员可看全租户；其余角色（含租户管理员）只统计账号绑定租户
        return ApiResponse.ok(analyticsService.overview(days, ConsolePermission.scopeTenant(tenantId)));
    }
}
