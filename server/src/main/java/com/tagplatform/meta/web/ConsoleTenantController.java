package com.tagplatform.meta.web;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.meta.model.AppCredentialInfo;
import com.tagplatform.meta.model.TenantInfo;
import com.tagplatform.meta.repo.TenantRepository;
import com.tagplatform.meta.service.CredentialService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 控制台 API · 租户与凭证页：租户列表/新建/启停、凭证列表/新建/重置密钥/启停。
 * 新建与重置返回一次性明文 Secret（仅此一次展示）。
 */
@RestController
@RequirePermission("page.tenants")
@RequestMapping({"/api/v1/console", "/console/api"})
public class ConsoleTenantController {

    private static final Pattern TENANT_ID_PATTERN = Pattern.compile("^[a-z][a-z0-9_]{2,63}$");

    private final TenantRepository tenantRepository;
    private final CredentialService credentialService;
    private final AuditLogService auditLogService;

    public ConsoleTenantController(TenantRepository tenantRepository, CredentialService credentialService,
                                   AuditLogService auditLogService) {
        this.tenantRepository = tenantRepository;
        this.credentialService = credentialService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/tenants")
    public ApiResponse<List<TenantInfo>> tenants() {
        ConsolePermission.requireAdminOrTenantAdmin();
        if ("TENANT_ADMIN".equals(RequestContext.getConsoleRole())) {
            String tenantId = requireOwnTenant();
            return ApiResponse.ok(tenantRepository.findByTenantId(tenantId)
                    .map(List::of).orElse(List.of()));
        }
        return ApiResponse.ok(tenantRepository.listAll());
    }

    @RequirePermission("op.tenant.manage")
    @PostMapping("/tenants")
    public ApiResponse<TenantInfo> createTenant(@RequestBody CreateTenantBody body) {
        ConsolePermission.requireAdmin();
        String tenantId = body.tenantId() == null ? "" : body.tenantId().trim();
        if (!TENANT_ID_PATTERN.matcher(tenantId).matches() || "PUBLIC".equalsIgnoreCase(tenantId)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "tenant_id 需满足 ^[a-z][a-z0-9_]{2,63}$（PUBLIC 为保留字）");
        }
        if (body.tenantName() == null || body.tenantName().isBlank()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "tenant_name 不能为空");
        }
        if (tenantRepository.findByTenantId(tenantId).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "租户已存在: " + tenantId);
        }
        int writeQps = body.writeQps() == null ? 2000 : body.writeQps();
        int readQps = body.readQps() == null ? 5000 : body.readQps();
        if (writeQps < 0 || readQps < 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "QPS 不能为负数");
        }
        String accessSystem = body.accessSystem() == null || body.accessSystem().isBlank()
                ? null : body.accessSystem().trim();
        tenantRepository.insert(tenantId, body.tenantName().trim(), accessSystem, writeQps, readQps);
        auditLogService.record("TENANT_CREATE", tenantId, null, null, null,
                "新建租户: " + body.tenantName().trim(), "success");
        return ApiResponse.ok(tenantRepository.findByTenantId(tenantId).orElseThrow());
    }

    @RequirePermission("op.tenant.manage")
    @PostMapping("/tenants/{tenantId}/status")
    public ApiResponse<Void> changeTenantStatus(@PathVariable("tenantId") String tenantId,
                                                @RequestBody StatusBody body) {
        ConsolePermission.requireAdmin();
        if (!"enabled".equals(body.status()) && !"disabled".equals(body.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 enabled/disabled");
        }
        int updated = tenantRepository.updateStatus(tenantId, body.status());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "租户不存在: " + tenantId);
        }
        auditLogService.record("TENANT_STATUS_CHANGE", tenantId, null, null, null,
                "租户状态变更为 " + body.status(), "success");
        return ApiResponse.ok(null);
    }

    @GetMapping("/credentials")
    public ApiResponse<List<AppCredentialInfo>> credentials(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        ConsolePermission.requireAdminOrTenantAdmin();
        if ("TENANT_ADMIN".equals(RequestContext.getConsoleRole())) {
            String own = requireOwnTenant();
            if (tenantId != null && !tenantId.isBlank() && !own.equals(tenantId)) {
                throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "不能查看其他租户凭证");
            }
            tenantId = own;
        }
        return ApiResponse.ok(credentialService.listByTenant(tenantId));
    }

    private String requireOwnTenant() {
        String tenant = RequestContext.getConsoleTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        return tenant;
    }

    @RequirePermission("op.tenant.manage")
    @PostMapping("/credentials")
    public ApiResponse<CredentialService.CredentialSecret> createCredential(@RequestBody CreateCredentialBody body) {
        ConsolePermission.requireAdmin();
        if (body.tenantId() == null || tenantRepository.findByTenantId(body.tenantId()).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "租户不存在: " + body.tenantId());
        }
        CredentialService.CredentialSecret created = credentialService.create(body.tenantId());
        auditLogService.record("CREDENTIAL_CREATE", body.tenantId(), null, null, null,
                "新建凭证: " + created.appKey(), "success");
        return ApiResponse.ok(created);
    }

    @RequirePermission("op.tenant.manage")
    @PostMapping("/credentials/{appKey}/reset")
    public ApiResponse<CredentialService.CredentialSecret> resetSecret(@PathVariable("appKey") String appKey) {
        ConsolePermission.requireAdmin();
        CredentialService.CredentialSecret reset = credentialService.resetSecret(appKey);
        auditLogService.record("CREDENTIAL_RESET", null, null, null, null,
                "重置凭证密钥: " + appKey, "success");
        return ApiResponse.ok(reset);
    }

    @RequirePermission("op.tenant.manage")
    @PostMapping("/credentials/{appKey}/status")
    public ApiResponse<Void> changeCredentialStatus(@PathVariable("appKey") String appKey,
                                                    @RequestBody StatusBody body) {
        ConsolePermission.requireAdmin();
        if (!"enabled".equals(body.status()) && !"disabled".equals(body.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 enabled/disabled");
        }
        int updated = credentialService.updateStatus(appKey, body.status());
        if (updated == 0) {
            throw new BizException(ErrorCode.APP_KEY_NOT_FOUND, "AppKey 不存在: " + appKey);
        }
        auditLogService.record("CREDENTIAL_STATUS_CHANGE", null, null, null, null,
                "凭证状态变更为 " + body.status() + ": " + appKey, "success");
        return ApiResponse.ok(null);
    }

    public record StatusBody(String status) {
    }

    public record CreateTenantBody(String tenantId, String tenantName, String accessSystem,
                                   Integer writeQps, Integer readQps) {
    }

    public record CreateCredentialBody(String tenantId) {
    }
}
