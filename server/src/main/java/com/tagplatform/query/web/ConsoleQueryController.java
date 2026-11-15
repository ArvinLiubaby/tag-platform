package com.tagplatform.query.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.query.repo.ObjectOptionRepository;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.HistoryPage;
import com.tagplatform.query.web.dto.ProfileView;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 控制台 API · 对象画像页 / 打标记录页（租户维度检索，默认包含待审核值）。
 */
@RestController
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleQueryController {

    private final ProfileQueryService profileQueryService;
    private final ObjectOptionRepository objectOptionRepository;

    public ConsoleQueryController(ProfileQueryService profileQueryService,
                                  ObjectOptionRepository objectOptionRepository) {
        this.profileQueryService = profileQueryService;
        this.objectOptionRepository = objectOptionRepository;
    }

    /** 画像页「对象 ID」下拉选项：已登记对象（含写入过/任务导入过的），支持关键字过滤，仅作输入辅助 */
    @RequirePermission("page.profile")
    @GetMapping("/objects/options")
    public ApiResponse<List<ObjectOptionRepository.Option>> objectOptions(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "objectType", required = false) String objectType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "200") int limit) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(objectOptionRepository.search(
                ConsolePermission.scopeTenant(tenantId), objectType, keyword, limit));
    }

    @RequirePermission("page.profile")
    @GetMapping("/objects/{objectType}/{objectId}/profile")
    public ApiResponse<ProfileView> profile(@PathVariable("objectType") String objectType,
                                            @PathVariable("objectId") String objectId,
                                            @RequestParam("tenantId") String tenantId,
                                            @RequestParam(value = "includePending", defaultValue = "true")
                                            boolean includePending) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(profileQueryService.profile(
                ConsolePermission.scopeTenant(tenantId), objectType, objectId, includePending));
    }

    @RequirePermission("page.records")
    @GetMapping("/tag-values/history")
    public ApiResponse<HistoryPage> history(
            @RequestParam("tenantId") String tenantId,
            @RequestParam(value = "operatorId", required = false) String operatorId,
            @RequestParam(value = "objectType", required = false) String objectType,
            @RequestParam(value = "objectId", required = false) String objectId,
            @RequestParam(value = "tagCode", required = false) String tagCode,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(profileQueryService.history(
                ConsolePermission.scopeTenant(tenantId), operatorId, objectType, objectId, tagCode,
                from, to, page, size));
    }
}
