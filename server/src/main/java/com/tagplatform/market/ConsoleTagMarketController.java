package com.tagplatform.market;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.console.security.RequirePermission;
import com.tagplatform.market.TagMarketModels.ListingView;
import com.tagplatform.market.TagMarketModels.RequestView;
import com.tagplatform.meta.model.TagMeta;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台 API · 标签市场：浏览、租户管理员上架/申请、平台管理员审批。
 */
@RestController
@RequirePermission("page.market")
@RequestMapping({"/api/v1/console/tag-market", "/console/api/tag-market"})
public class ConsoleTagMarketController {

    private final TagMarketService marketService;
    private final AuditLogService auditLogService;

    public ConsoleTagMarketController(TagMarketService marketService, AuditLogService auditLogService) {
        this.marketService = marketService;
        this.auditLogService = auditLogService;
    }

    @GetMapping("/listings")
    public ApiResponse<List<ListingView>> listings(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "objectType", required = false) String objectType) {
        ConsolePermission.requireAny();
        return ApiResponse.ok(marketService.listings(keyword, objectType));
    }

    @RequirePermission("op.market.list")
    @GetMapping("/eligible")
    public ApiResponse<List<TagMeta>> eligible() {
        ConsolePermission.requireRole("TENANT_ADMIN");
        return ApiResponse.ok(marketService.eligibleTags());
    }

    @RequirePermission("op.market.list")
    @PostMapping("/listings")
    public ApiResponse<ListingView> list(@RequestBody ListTagBody body) {
        ConsolePermission.requireRole("TENANT_ADMIN");
        ListingView view = marketService.list(body.tagCode(), body.description());
        auditLogService.record("TAG_MARKET_LIST", null, body.tagCode(), null, body.tagCode(),
                "标签上架市场", "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.market.apply")
    @PostMapping("/listings/{id}/apply")
    public ApiResponse<RequestView> apply(@PathVariable("id") long id, @RequestBody ApplyBody body) {
        ConsolePermission.requireRole("TENANT_ADMIN");
        RequestView view = marketService.apply(id, body.remark());
        auditLogService.record("TAG_MARKET_APPLY", view.applicantTenantId(), null, null, view.tagCode(),
                "申请使用市场标签", "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.market.requests")
    @GetMapping("/requests")
    public ApiResponse<List<RequestView>> requests(
            @RequestParam(value = "status", required = false) String status) {
        ConsolePermission.requireAdminOrTenantAdmin();
        return ApiResponse.ok(marketService.requests(status));
    }

    @RequirePermission("op.market.review")
    @PostMapping("/requests/{id}/approve")
    public ApiResponse<RequestView> approve(@PathVariable("id") long id, @RequestBody ReviewBody body) {
        ConsolePermission.requireRole("ADMIN");
        RequestView view = marketService.approve(id, body.remark());
        auditLogService.record("TAG_MARKET_APPROVE", view.applicantTenantId(), null, null, view.tagCode(),
                "标签市场申请审批通过", "success");
        return ApiResponse.ok(view);
    }

    @RequirePermission("op.market.review")
    @PostMapping("/requests/{id}/reject")
    public ApiResponse<RequestView> reject(@PathVariable("id") long id, @RequestBody ReviewBody body) {
        ConsolePermission.requireRole("ADMIN");
        RequestView view = marketService.reject(id, body.remark());
        auditLogService.record("TAG_MARKET_REJECT", view.applicantTenantId(), null, null, view.tagCode(),
                "标签市场申请驳回", "success");
        return ApiResponse.ok(view);
    }

    public record ListTagBody(String tagCode, String description) {
    }

    public record ApplyBody(String remark) {
    }

    public record ReviewBody(String remark) {
    }
}
