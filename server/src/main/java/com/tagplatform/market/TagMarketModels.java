package com.tagplatform.market;

import java.time.LocalDateTime;

public final class TagMarketModels {

    private TagMarketModels() {
    }

    public record ListingRow(
            long id,
            String tagCode,
            String ownerTenantId,
            String description,
            String status,
            String listedBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record RequestRow(
            long id,
            long listingId,
            String tagCode,
            String applicantTenantId,
            String applicantUser,
            String remark,
            String status,
            String reviewedBy,
            String reviewRemark,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt) {
    }

    public record ListingView(
            long id,
            String tagCode,
            String tagName,
            String categoryCode,
            String objectType,
            String valueType,
            String valueRange,
            boolean valueMultiple,
            boolean needValueAudit,
            String sensitivity,
            String ownerTenantId,
            String ownerTenantName,
            String description,
            String listedBy,
            LocalDateTime createdAt,
            boolean availableToCurrentTenant,
            boolean ownedByCurrentTenant,
            String applicationStatus,
            boolean canApply) {
    }

    public record RequestView(
            long id,
            long listingId,
            String tagCode,
            String tagName,
            String applicantTenantId,
            String applicantTenantName,
            String applicantUser,
            String remark,
            String status,
            String reviewedBy,
            String reviewRemark,
            LocalDateTime createdAt,
            LocalDateTime reviewedAt) {
    }
}
