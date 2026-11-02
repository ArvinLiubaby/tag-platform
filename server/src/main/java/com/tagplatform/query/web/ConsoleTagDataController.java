package com.tagplatform.query.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.query.service.TagLatestService;
import com.tagplatform.query.web.dto.TagLatestPage;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 控制台 API · 标签查看页：标签当前值（latest / 组合标签抽样计算）。
 * 打标流水复用 /tag-values/history 接口。
 */
@RestController
@RequirePermission("page.tag_view")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleTagDataController {

    private final TagLatestService tagLatestService;

    public ConsoleTagDataController(TagLatestService tagLatestService) {
        this.tagLatestService = tagLatestService;
    }

    @GetMapping("/tag-values/latest")
    public ApiResponse<TagLatestPage> latest(@RequestParam("tenantId") String tenantId,
                                             @RequestParam("tagCode") String tagCode,
                                             @RequestParam(value = "limit", defaultValue = "50") int limit) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagLatestService.latest(ConsolePermission.scopeTenant(tenantId), tagCode, limit));
    }
}
