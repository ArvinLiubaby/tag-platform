package com.tagplatform.meta.composition;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
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

/**
 * 控制台 API · 标签组合：派生标签的列表、详情、预览、保存、发布/停用与回滚。
 * 平台管理员可维护全部；租户管理员仅可维护归属本租户的组合。
 */
@RestController
@RequirePermission("page.tag_compose")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleCompositionController {

    private final TagCompositionService service;
    private final AuditLogService auditLogService;

    public ConsoleCompositionController(TagCompositionService service, AuditLogService auditLogService) {
        this.service = service;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/tag-compositions")
    public ApiResponse<List<CompositionModels.CompositionView>> list(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "status", required = false) String status) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(service.list(keyword, status));
    }

    @GetMapping("/tag-compositions/{tagCode}")
    public ApiResponse<CompositionModels.CompositionView> detail(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(service.detail(tagCode));
    }

    /** 预检：给定对象与规则，返回参与标签当前值、逐条判定与结果，不落库 */
    @PostMapping("/tag-compositions/preview")
    public ApiResponse<CompositionModels.PreviewResponse> preview(
            @RequestBody CompositionModels.PreviewRequest request) {
        ConsolePermission.requireAny();
        String tenantId = ConsolePermission.scopeTenant(request.tenantId());
        CompositionModels.PreviewRequest scoped = new CompositionModels.PreviewRequest(tenantId,
                request.objectType(), request.objectId(), request.outputType(), request.rule());
        return ApiResponse.ok(service.preview(scoped));
    }

    @RequirePermission("op.composition.manage")
    @PostMapping("/tag-compositions")
    public ApiResponse<CompositionModels.CompositionView> create(
            @RequestBody CompositionModels.SaveRequest request) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.create(request);
        auditLogService.record("TAG_COMPOSITION_CREATE", view.tenantId().split(",")[0].trim(), view.objectType(),
                null, view.tagCode(), "创建组合标签: " + view.tagName() + "（草稿）", "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.composition.manage")
    @PutMapping("/tag-compositions/{tagCode}")
    public ApiResponse<CompositionModels.CompositionView> update(@PathVariable("tagCode") String tagCode,
                                                                 @RequestBody CompositionModels.SaveRequest request) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.update(tagCode, request);
        auditLogService.record("TAG_COMPOSITION_UPDATE", view.tenantId().split(",")[0].trim(), view.objectType(),
                null, tagCode, "更新组合规则: " + view.tagName() + "（v" + view.metaVersion() + "）", "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.composition.manage")
    @PostMapping("/tag-compositions/{tagCode}/publish")
    public ApiResponse<CompositionModels.CompositionView> publish(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.publish(tagCode);
        auditLogService.record("TAG_COMPOSITION_PUBLISH", view.tenantId().split(",")[0].trim(), view.objectType(),
                null, tagCode, "发布组合标签: " + view.tagName(), "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.composition.manage")
    @PostMapping("/tag-compositions/{tagCode}/disable")
    public ApiResponse<CompositionModels.CompositionView> disable(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.disable(tagCode);
        auditLogService.record("TAG_COMPOSITION_DISABLE", view.tenantId().split(",")[0].trim(), view.objectType(),
                null, tagCode, "停用组合标签: " + view.tagName(), "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.composition.manage")
    @PostMapping("/tag-compositions/{tagCode}/rollback/{version}")
    public ApiResponse<CompositionModels.CompositionView> rollback(@PathVariable("tagCode") String tagCode,
                                                                   @PathVariable("version") int version) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.rollback(tagCode, version);
        auditLogService.record("TAG_COMPOSITION_ROLLBACK", view.tenantId().split(",")[0].trim(), view.objectType(),
                null, tagCode, "组合规则回滚到 v" + version + "（生成 v" + view.metaVersion() + "）", "success");
        return ApiResponse.ok(view);
    }

    /** 删除组合：规则删除、派生标签归档；被引用或已上架市场时拒绝 */
    @RequirePermission("op.composition.manage")
    @org.springframework.web.bind.annotation.DeleteMapping("/tag-compositions/{tagCode}")
    public ApiResponse<Void> delete(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAdminOrTenantAdmin();
        CompositionModels.CompositionView view = service.detail(tagCode);
        service.delete(tagCode);
        auditLogService.record("TAG_COMPOSITION_DELETE", view.tenantId().split(",")[0].trim(),
                view.objectType(), null, tagCode, "删除组合标签: " + view.tagName() + "（派生标签归档）", "success");
        return ApiResponse.ok(null);
    }

    /** 标签查看：当前值（latest）、被引用情况与最近流水 */
    @GetMapping("/tags/{tagCode}/usage")
    public ApiResponse<CompositionModels.UsageView> usage(@PathVariable("tagCode") String tagCode,
                                                          @RequestParam(value = "tenantId", required = false)
                                                          String tenantId) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(service.usage(tagCode, tenantId));
    }
}
