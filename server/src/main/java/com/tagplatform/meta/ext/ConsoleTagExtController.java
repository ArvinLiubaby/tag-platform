package com.tagplatform.meta.ext;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 控制台 API · 标签扩展信息与一级维度优先级。
 *
 * <p>扩展属性定义支持增删（status 停用），值按租户隔离；标签列表页可用
 * {@code /tag-ext-summary} 一次性取全部标签的扩展值用于渲染。
 */
@RestController
@RequirePermission("page.tags")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleTagExtController {

    private final TagExtService service;

    public ConsoleTagExtController(TagExtService service) {
        this.service = service;
    }

    // ---------------------------------------------------------- 属性定义

    @GetMapping("/tag-ext-defs")
    public ApiResponse<List<TagExtModels.AttrDef>> defs(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.ok(service.listDefs(service.resolveTenant(tenantId)));
    }

    @PostMapping("/tag-ext-defs")
    public ApiResponse<TagExtModels.AttrDef> createDef(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody TagExtModels.DefRequest request) {
        return ApiResponse.ok(service.createDef(service.resolveTenant(tenantId), request));
    }

    @PutMapping("/tag-ext-defs/{attrKey}")
    public ApiResponse<TagExtModels.AttrDef> updateDef(
            @PathVariable("attrKey") String attrKey,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody TagExtModels.DefRequest request) {
        return ApiResponse.ok(service.updateDef(service.resolveTenant(tenantId), attrKey, request));
    }

    @PostMapping("/tag-ext-defs/{attrKey}/status")
    public ApiResponse<Void> changeDefStatus(
            @PathVariable("attrKey") String attrKey,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody TagExtModels.StatusRequest request) {
        service.changeDefStatus(service.resolveTenant(tenantId), attrKey,
                request == null || request.status() == null ? "" : request.status());
        return ApiResponse.ok(null);
    }

    // ---------------------------------------------------------- 标签扩展值

    @GetMapping("/tags/{tagCode}/ext")
    public ApiResponse<TagExtModels.TagExtView> tagExt(
            @PathVariable("tagCode") String tagCode,
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.ok(service.tagExt(service.resolveTenant(tenantId), tagCode));
    }

    @PutMapping("/tags/{tagCode}/ext")
    public ApiResponse<TagExtModels.TagExtView> saveTagExt(
            @PathVariable("tagCode") String tagCode,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody TagExtModels.SaveExtRequest request) {
        return ApiResponse.ok(service.saveTagExt(service.resolveTenant(tenantId), tagCode, request));
    }

    /** 列表页批量取值：{ tagCode: { attrKey: value } } */
    @GetMapping("/tag-ext-summary")
    public ApiResponse<Map<String, Map<String, String>>> summary(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.ok(service.extSummary(service.resolveTenant(tenantId)));
    }

    // ---------------------------------------------------------- 一级维度优先级

    @GetMapping("/tag-dim-priorities")
    public ApiResponse<List<TagExtModels.DimPriority>> dimPriorities(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.ok(service.dimPriorities(service.resolveTenant(tenantId)));
    }

    @PutMapping("/tag-dim-priorities")
    public ApiResponse<List<TagExtModels.DimPriority>> saveDimPriorities(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestBody TagExtModels.DimPriorityRequest request) {
        return ApiResponse.ok(service.saveDimPriorities(service.resolveTenant(tenantId), request));
    }
}
