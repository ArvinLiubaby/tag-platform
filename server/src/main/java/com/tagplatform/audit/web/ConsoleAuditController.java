package com.tagplatform.audit.web;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.audit.AuditValueService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.write.model.TagValue;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 控制台 API · 待审核队列页 / 审计日志页。
 */
@RestController
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleAuditController {

    private final AuditValueService auditValueService;
    private final AuditLogService auditLogService;

    public ConsoleAuditController(AuditValueService auditValueService, AuditLogService auditLogService) {
        this.auditValueService = auditValueService;
        this.auditLogService = auditLogService;
    }

    @RequirePermission("page.audit_pending")
    @GetMapping("/audit/pending")
    public ApiResponse<List<TagValue>> pending(@RequestParam(value = "limit", defaultValue = "200") int limit) {
        ConsolePermission.requireRole("ADMIN", "TENANT_ADMIN", "AUDITOR");
        String tenantId = "TENANT_ADMIN".equals(RequestContext.getConsoleRole())
                ? ConsolePermission.scopeTenant(null) : null;
        return ApiResponse.ok(auditValueService.pending(tenantId, limit));
    }

    @RequirePermission("op.audit.value")
    @PostMapping("/audit/approve")
    public ApiResponse<TagValue> approve(@RequestBody DecisionBody body) {
        ConsolePermission.requireRole("ADMIN", "AUDITOR");
        checkBody(body);
        return ApiResponse.ok(auditValueService.approve(body.tenantId(), body.objectType(), body.objectId(),
                body.tagCode(), RequestContext.getConsoleDisplayName(), body.auditRemark()));
    }

    @RequirePermission("op.audit.value")
    @PostMapping("/audit/reject")
    public ApiResponse<TagValue> reject(@RequestBody DecisionBody body) {
        ConsolePermission.requireRole("ADMIN", "AUDITOR");
        checkBody(body);
        return ApiResponse.ok(auditValueService.reject(body.tenantId(), body.objectType(), body.objectId(),
                body.tagCode(), RequestContext.getConsoleDisplayName(), body.auditRemark()));
    }

    @RequirePermission("page.logs")
    @GetMapping("/audit-logs")
    public ApiResponse<AuditLogService.AuditPage> logs(
            @RequestParam(value = "actionType", required = false) String actionType,
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "from", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime from,
            @RequestParam(value = "to", required = false)
            @DateTimeFormat(pattern = "yyyy-MM-dd HH:mm:ss") LocalDateTime to,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        ConsolePermission.requireRole("ADMIN", "TENANT_ADMIN", "AUDITOR");
        return ApiResponse.ok(auditLogService.list(
                actionType, ConsolePermission.scopeTenant(tenantId), from, to, page, size));
    }

    private void checkBody(DecisionBody body) {
        if (body == null || body.tenantId() == null || body.objectType() == null
                || body.objectId() == null || body.tagCode() == null) {
            throw new BizException(ErrorCode.PARAM_MISSING,
                    "tenant_id/object_type/object_id/tag_code 均不能为空");
        }
    }

    /** 审核决策请求（四元素定位待审核值） */
    public record DecisionBody(
            String tenantId,
            String objectType,
            String objectId,
            String tagCode,
            String auditRemark) {
    }
}
