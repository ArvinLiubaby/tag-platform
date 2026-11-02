package com.tagplatform.meta.composition;

import java.time.LocalDateTime;

/**
 * 组合标签定义（tag_composition 表映射）：与派生标签 tag_meta 一一对应。
 * status：draft（草稿，标签离线）/ published（已发布，标签在线）/ disabled（已停用，标签离线）。
 */
public record TagComposition(
        Long id,
        String tagCode,
        String tenantId,
        String objectType,
        String logic,
        String conditionsJson,
        String outputType,
        String outputJson,
        String missingPolicy,
        String description,
        String status,
        int ruleVersion,
        String createdBy,
        String updatedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public static final String STATUS_DRAFT = "draft";
    public static final String STATUS_PUBLISHED = "published";
    public static final String STATUS_DISABLED = "disabled";

    public boolean visibleTo(String requesterTenantId) {
        if (tenantId == null || requesterTenantId == null) {
            return false;
        }
        for (String token : tenantId.split(",")) {
            String id = token.trim();
            if ("PUBLIC".equals(id) || id.equals(requesterTenantId)) {
                return true;
            }
        }
        return false;
    }

    public boolean ownedByTenant(String tenant) {
        if (tenant == null || tenant.isBlank() || tenantId == null) {
            return false;
        }
        String first = tenantId.split(",")[0].trim();
        return !"PUBLIC".equals(first) && first.equals(tenant);
    }
}
