package com.tagplatform.market;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsolePermission;
import com.tagplatform.market.TagMarketModels.ListingRow;
import com.tagplatform.market.TagMarketModels.ListingView;
import com.tagplatform.market.TagMarketModels.RequestRow;
import com.tagplatform.market.TagMarketModels.RequestView;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.repo.TenantRepository;
import com.tagplatform.meta.service.TagMetaService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签市场：租户管理员上架本租户可用标签，其他租户申请使用，平台管理员审批后扩展适用租户。
 * 上架不改变标签适用范围；只有审批通过才由 TagMetaService.grantTenant 扩展租户。
 */
@Service
public class TagMarketService {

    private static final Set<String> REQUEST_STATUSES = Set.of("pending", "approved", "rejected");

    private final TagMarketRepository marketRepository;
    private final TagMetaRepository tagMetaRepository;
    private final TagMetaService tagMetaService;
    private final TenantRepository tenantRepository;

    public TagMarketService(TagMarketRepository marketRepository, TagMetaRepository tagMetaRepository,
                            TagMetaService tagMetaService, TenantRepository tenantRepository) {
        this.marketRepository = marketRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.tagMetaService = tagMetaService;
        this.tenantRepository = tenantRepository;
    }

    public List<ListingView> listings(String keyword, String objectType) {
        String actorTenant = RequestContext.getConsoleTenantId();
        String actorRole = RequestContext.getConsoleRole();
        List<ListingRow> rows = marketRepository.listListings();
        Map<String, TagMeta> tagMap = tagMetaRepository.findByCodes(
                        rows.stream().map(ListingRow::tagCode).toList())
                .stream().collect(Collectors.toMap(TagMeta::tagCode, tag -> tag));
        Map<String, String> tenantNames = tenantNames();

        Map<Long, RequestRow> latestRequests = new LinkedHashMap<>();
        if (actorTenant != null && !actorTenant.isBlank()) {
            for (RequestRow request : marketRepository.listRequestsForTenant(actorTenant, null)) {
                latestRequests.putIfAbsent(request.listingId(), request);
            }
        }

        String keywordLower = keyword == null ? null : keyword.trim().toLowerCase(Locale.ROOT);
        return rows.stream()
                .map(row -> {
                    TagMeta tag = tagMap.get(row.tagCode());
                    if (tag == null || !tag.online()) {
                        return null;
                    }
                    if (objectType != null && !objectType.isBlank() && !tag.supportsObjectType(objectType.trim())) {
                        return null;
                    }
                    if (keywordLower != null && !keywordLower.isBlank()
                            && !tag.tagCode().toLowerCase(Locale.ROOT).contains(keywordLower)
                            && !tag.tagName().toLowerCase(Locale.ROOT).contains(keywordLower)) {
                        return null;
                    }
                    return toListingView(row, tag, tenantNames, actorTenant, actorRole,
                            latestRequests.get(row.id()));
                })
                .filter(view -> view != null)
                .toList();
    }

    public List<TagMeta> eligibleTags() {
        requireTenantAdmin();
        String tenant = requireActorTenant();
        Set<String> listed = Set.copyOf(marketRepository.listedTagCodes());
        return tagMetaRepository.listVisibleOnline(tenant).stream()
                .filter(tag -> !listed.contains(tag.tagCode()))
                .toList();
    }

    @Transactional
    public ListingView list(String tagCode, String description) {
        requireTenantAdmin();
        String tenant = requireActorTenant();
        TagMeta tag = tagMetaRepository.findByCode(tagCode)
                .filter(meta -> meta.visibleTo(tenant))
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在或本租户不可用: " + tagCode));
        if (!tag.online()) {
            throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签未上线，不能上架: " + tagCode);
        }
        if (marketRepository.findListingByTag(tagCode).isPresent()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已在市场: " + tagCode);
        }
        String normalizedDescription = trimToNull(description);
        if (normalizedDescription != null && normalizedDescription.length() > 512) {
            throw new BizException(ErrorCode.PARAM_INVALID, "market description 超长（上限 512）");
        }
        long id = marketRepository.insertListing(tagCode, tenant, normalizedDescription, actorName());
        ListingRow row = marketRepository.findListing(id).orElseThrow();
        return toListingView(row, tag, tenantNames(), tenant, RequestContext.getConsoleRole(), null);
    }

    public RequestView apply(long listingId, String remark) {
        requireTenantAdmin();
        String tenant = requireActorTenant();
        ListingRow listing = marketRepository.findListing(listingId)
                .filter(row -> "listed".equals(row.status()))
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "市场标签不存在或已下架"));
        TagMeta tag = tagMetaRepository.findByCode(listing.tagCode())
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + listing.tagCode()));
        if (!tag.online()) {
            throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签未上线: " + tag.tagCode());
        }
        if (tenant.equals(listing.ownerTenantId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能申请本租户上架的标签");
        }
        if (tag.visibleTo(tenant)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "标签已对本租户开放，无需申请");
        }
        RequestRow latest = marketRepository.findLatestRequest(listingId, tenant).orElse(null);
        if (latest != null && "pending".equals(latest.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已有待审批申请，请勿重复提交");
        }
        if (latest != null && "approved".equals(latest.status()) && !tag.visibleTo(tenant)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "申请已审批通过，但标签授权尚未生效，请联系平台管理员");
        }
        String normalizedRemark = trimToNull(remark);
        if (normalizedRemark != null && normalizedRemark.length() > 512) {
            throw new BizException(ErrorCode.PARAM_INVALID, "申请说明超长（上限 512）");
        }
        long id = marketRepository.insertRequest(listingId, listing.tagCode(), tenant, actorName(), normalizedRemark);
        return requestView(marketRepository.findRequest(id).orElseThrow());
    }

    public List<RequestView> requests(String status) {
        String role = RequestContext.getConsoleRole();
        String normalizedStatus = trimToNull(status);
        if (normalizedStatus != null && !REQUEST_STATUSES.contains(normalizedStatus)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "status 仅支持 pending/approved/rejected");
        }
        List<RequestRow> rows;
        if ("ADMIN".equals(role)) {
            rows = marketRepository.listRequests(normalizedStatus);
        } else if ("TENANT_ADMIN".equals(role)) {
            rows = marketRepository.listRequestsForTenant(requireActorTenant(), normalizedStatus);
        } else {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN);
        }
        return rows.stream().map(this::requestView).toList();
    }

    @Transactional
    public RequestView approve(long requestId, String remark) {
        ConsolePermission.requireRole("ADMIN");
        RequestRow request = requirePending(requestId);
        ListingRow listing = marketRepository.findListing(request.listingId())
                .filter(row -> "listed".equals(row.status()))
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "市场标签已下架"));
        TagMeta tag = tagMetaRepository.findByCode(request.tagCode())
                .orElseThrow(() -> new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在: " + request.tagCode()));
        if (!tag.online()) {
            throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签未上线: " + request.tagCode());
        }
        String reviewRemark = trimToNull(remark);
        tagMetaService.grantTenant(request.tagCode(), request.applicantTenantId(), actorName());
        int updated = marketRepository.decideRequest(requestId, "approved", actorName(), reviewRemark);
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "申请单已处理，请刷新后重试");
        }
        return requestView(marketRepository.findRequest(requestId).orElseThrow());
    }

    @Transactional
    public RequestView reject(long requestId, String remark) {
        ConsolePermission.requireRole("ADMIN");
        RequestRow request = requirePending(requestId);
        String reviewRemark = trimToNull(remark);
        if (reviewRemark == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "驳回必须填写原因");
        }
        int updated = marketRepository.decideRequest(requestId, "rejected", actorName(), reviewRemark);
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "申请单已处理，请刷新后重试");
        }
        return requestView(marketRepository.findRequest(requestId).orElseThrow());
    }

    private RequestRow requirePending(long requestId) {
        RequestRow request = marketRepository.findRequest(requestId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "申请单不存在: " + requestId));
        if (!"pending".equals(request.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "申请单已处理");
        }
        return request;
    }

    private ListingView toListingView(ListingRow row, TagMeta tag, Map<String, String> tenantNames,
                                      String actorTenant, String actorRole, RequestRow latestRequest) {
        boolean available = actorTenant != null && !actorTenant.isBlank() && tag.visibleTo(actorTenant);
        boolean owned = actorTenant != null && actorTenant.equals(row.ownerTenantId());
        String applicationStatus = latestRequest == null ? null : latestRequest.status();
        boolean canApply = "TENANT_ADMIN".equals(actorRole) && !owned && !available
                && (applicationStatus == null || "rejected".equals(applicationStatus));
        return new ListingView(row.id(), tag.tagCode(), tag.tagName(), tag.categoryCode(), tag.objectType(),
                tag.valueType(), tag.valueRange(), tag.valueMultiple(), tag.needValueAudit(), tag.sensitivity(),
                row.ownerTenantId(), tenantNames.getOrDefault(row.ownerTenantId(), row.ownerTenantId()),
                row.description(), row.listedBy(), row.createdAt(), available, owned, applicationStatus, canApply);
    }

    private RequestView requestView(RequestRow row) {
        TagMeta tag = tagMetaRepository.findByCode(row.tagCode()).orElse(null);
        String applicantTenantName = tenantRepository.findByTenantId(row.applicantTenantId())
                .map(tenant -> tenant.tenantName()).orElse(row.applicantTenantId());
        return new RequestView(row.id(), row.listingId(), row.tagCode(),
                tag == null ? row.tagCode() : tag.tagName(), row.applicantTenantId(), applicantTenantName,
                row.applicantUser(), row.remark(), row.status(), row.reviewedBy(), row.reviewRemark(),
                row.createdAt(), row.reviewedAt());
    }

    private void requireTenantAdmin() {
        ConsolePermission.requireRole("TENANT_ADMIN");
    }

    private String requireActorTenant() {
        String tenant = RequestContext.getConsoleTenantId();
        if (tenant == null || tenant.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前账号未绑定租户");
        }
        return tenant;
    }

    private Map<String, String> tenantNames() {
        return tenantRepository.listAll().stream()
                .collect(Collectors.toMap(tenant -> tenant.tenantId(), tenant -> tenant.tenantName(),
                        (a, b) -> a));
    }

    private String actorName() {
        String name = RequestContext.getConsoleDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String username = RequestContext.getConsoleUsername();
        return username == null || username.isBlank() ? "system" : username;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
