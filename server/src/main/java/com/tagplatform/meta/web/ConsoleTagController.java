package com.tagplatform.meta.web;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.model.TagVersion;
import com.tagplatform.meta.repo.TagAuditRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.service.TagMetaService;
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
 * 控制台 API · 标签字典页：列表 / 类目 / 注册 / 上下线 / 定义审核流水与审批。
 * 平台管理员管理全部标签；租户管理员仅可管理归属本租户（tenant_id 第一个租户）的标签。
 */
@RestController
@RequirePermission("page.tags")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleTagController {

    private final TagMetaService tagMetaService;
    private final AuditLogService auditLogService;

    public ConsoleTagController(TagMetaService tagMetaService, AuditLogService auditLogService) {
        this.tagMetaService = tagMetaService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/tags")
    public ApiResponse<List<TagMeta>> tags(@RequestParam(value = "status", required = false) String status,
                                           @RequestParam(value = "keyword", required = false) String keyword,
                                           @RequestParam(value = "tenantId", required = false) String tenantId,
                                           @RequestParam(value = "sensitivity", required = false) String sensitivity,
                                           @RequestParam(value = "objectType", required = false) String objectType,
                                           @RequestParam(value = "includeArchived", defaultValue = "false")
                                           boolean includeArchived) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagMetaService.list(status, keyword,
                ConsolePermission.scopeTenant(tenantId), sensitivity, objectType, includeArchived));
    }

    @GetMapping("/tag-categories")
    public ApiResponse<List<TagMetaRepository.CategoryInfo>> categories() {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagMetaService.listCategories());
    }

    @RequirePermission("op.tag.manage")
    @PostMapping("/tags")
    public ApiResponse<TagMeta> register(@RequestBody TagMetaService.RegisterRequest request) {
        ConsolePermission.requireAdminOrTenantAdmin();
        TagMeta meta = tagMetaService.register(request, RequestContext.getConsoleDisplayName());
        auditLogService.record("TAG_REGISTER", null, null, null, request.tagCode(),
                "注册标签定义: " + request.tagName(), "success");
        return ApiResponse.ok(meta);
    }

    /** 修改标签定义：tag_code / tenant_id 不可变，立即生效并记录审计 */
    @RequirePermission("op.tag.manage")
    @PutMapping("/tags/{tagCode}")
    public ApiResponse<TagMeta> update(@PathVariable("tagCode") String tagCode,
                                       @RequestBody TagMetaService.UpdateRequest request) {
        ConsolePermission.requireAdminOrTenantAdmin();
        TagMeta meta = tagMetaService.update(tagCode, request);
        auditLogService.record("TAG_UPDATE", null, null, null, tagCode,
                "修改标签定义: " + meta.tagName() + "（version " + meta.version() + "）", "success");
        return ApiResponse.ok(meta);
    }

    @GetMapping("/tags/{tagCode}/versions")
    public ApiResponse<List<TagVersion.Summary>> versions(
            @PathVariable("tagCode") String tagCode,
            @RequestParam(value = "limit", defaultValue = "100") int limit) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagMetaService.listVersions(tagCode, limit));
    }

    @GetMapping("/tags/{tagCode}/versions/{version}")
    public ApiResponse<TagVersion.Detail> versionDetail(@PathVariable("tagCode") String tagCode,
                                                        @PathVariable("version") int version) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagMetaService.versionDetail(tagCode, version));
    }

    @RequirePermission("op.tag.version.rollback")
    @PostMapping("/tags/{tagCode}/versions/{version}/rollback")
    public ApiResponse<TagMeta> rollback(@PathVariable("tagCode") String tagCode,
                                         @PathVariable("version") int version) {
        ConsolePermission.requireAdminOrTenantAdmin();
        TagMeta updated = tagMetaService.rollback(tagCode, version);
        auditLogService.record("TAG_ROLLBACK", null, null, null, tagCode,
                "回滚标签定义到 v" + version + "（生成 v" + updated.version() + "）", "success");
        return ApiResponse.ok(updated);
    }

    @RequirePermission("op.tag.manage")
    @PostMapping("/tags/{tagCode}/status")
    public ApiResponse<Void> changeStatus(@PathVariable("tagCode") String tagCode, @RequestBody StatusBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        tagMetaService.changeStatus(tagCode, body.status());
        auditLogService.record("TAG_STATUS_CHANGE", null, null, null, tagCode,
                "标签状态变更为 " + body.status(), "success");
        return ApiResponse.ok(null);
    }

    @RequirePermission("op.tag.manage")
    @PostMapping("/tags/{tagCode}/writable")
    public ApiResponse<Void> changeWritable(@PathVariable("tagCode") String tagCode, @RequestBody WritableBody body) {
        ConsolePermission.requireAdminOrTenantAdmin();
        if (body.writable() == null) {
            throw new BizException(ErrorCode.PARAM_INVALID, "writable 不能为空（true=允许写入 / false=禁止写入）");
        }
        tagMetaService.changeWritable(tagCode, body.writable());
        auditLogService.record("TAG_WRITABLE_CHANGE", null, null, null, tagCode,
                "标签写入开关变更为 " + body.writable(), "success");
        return ApiResponse.ok(null);
    }

    /** 归档（软删除）：仅已下线标签；历史值与审计记录保留，tag_code 不可复用 */
    @RequirePermission("op.tag.manage")
    @PostMapping("/tags/{tagCode}/archive")
    public ApiResponse<Void> archive(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAdminOrTenantAdmin();
        tagMetaService.archive(tagCode);
        auditLogService.record("TAG_ARCHIVE", null, null, null, tagCode,
                "归档标签定义（软删除，历史数据保留）", "success");
        return ApiResponse.ok(null);
    }

    /** 恢复归档标签为已下线状态 */
    @RequirePermission("op.tag.manage")
    @PostMapping("/tags/{tagCode}/restore")
    public ApiResponse<Void> restore(@PathVariable("tagCode") String tagCode) {
        ConsolePermission.requireAdminOrTenantAdmin();
        tagMetaService.restore(tagCode);
        auditLogService.record("TAG_RESTORE", null, null, null, tagCode,
                "恢复已归档标签为已下线", "success");
        return ApiResponse.ok(null);
    }

    @GetMapping("/tag-audits")
    public ApiResponse<List<TagAuditRepository.TagAuditInfo>> tagAudits(
            @RequestParam(value = "status", required = false) String status) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(tagMetaService.listAudits(status));
    }

    @RequirePermission("op.tag.audit")
    @PostMapping("/tag-audits/{id}/approve")
    public ApiResponse<Void> approve(@PathVariable("id") long id, @RequestBody AuditBody body) {
        ConsolePermission.requireAdmin();
        tagMetaService.decideAudit(id, true, RequestContext.getConsoleDisplayName(), body.auditRemark());
        auditLogService.record("TAG_AUDIT_APPROVE", null, null, null, null,
                "标签定义审核单通过: " + id, "success");
        return ApiResponse.ok(null);
    }

    @RequirePermission("op.tag.audit")
    @PostMapping("/tag-audits/{id}/reject")
    public ApiResponse<Void> reject(@PathVariable("id") long id, @RequestBody AuditBody body) {
        ConsolePermission.requireAdmin();
        tagMetaService.decideAudit(id, false, RequestContext.getConsoleDisplayName(), body.auditRemark());
        auditLogService.record("TAG_AUDIT_REJECT", null, null, null, null,
                "标签定义审核单驳回: " + id, "success");
        return ApiResponse.ok(null);
    }

    public record StatusBody(String status) {
    }

    public record WritableBody(Boolean writable) {
    }

    public record AuditBody(String auditRemark) {
    }
}
