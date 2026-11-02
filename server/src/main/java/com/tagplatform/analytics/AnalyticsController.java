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
            @RequestParam(value = "days", defaultValue = "7") int days) {
        ConsolePermission.requireRole("ADMIN", "AUDITOR", "VIEWER", "TAGGER", "TASK_MANAGER");
        return ApiResponse.ok(analyticsService.overview(days));
    }
}
