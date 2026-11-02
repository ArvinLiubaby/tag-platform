package com.tagplatform.meta.model;

import java.time.LocalDateTime;

/**
 * 标签元数据（tag_meta 表映射）。
 */
public record TagMeta(
        Long id,
        String tagCode,
        String tagName,
        String categoryCode,
        String valueType,
        String valueRange,
        boolean valueMultiple,
        String tagType,
        boolean needValueAudit,
        int valueTtlDays,
        String tenantId,
        String sensitivity,
        String status,
        int version,
        String objectType,
        String sourceMode,
        boolean writable,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    /** 是否对当前租户可见：PUBLIC（全部租户）或适用租户列表包含请求租户 */
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

    /** 标签归属：适用租户列表的第一个租户；PUBLIC 开头视为平台标签 */
    public boolean ownedByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank() || this.tenantId == null) {
            return false;
        }
        String first = this.tenantId.split(",")[0].trim();
        return !"PUBLIC".equals(first) && first.equals(tenantId);
    }

    public boolean online() {
        return "online".equalsIgnoreCase(status);
    }

    /**
     * 标签-对象绑定校验：object_type 未声明（空）= 不限；
     * 声明后为逗号分隔多值（如 driver,vehicle），必须包含目标对象类型。
     */
    public boolean supportsObjectType(String targetObjectType) {
        if (objectType == null || objectType.isBlank()) {
            return true;
        }
        for (String type : objectType.split(",")) {
            if (type.trim().equalsIgnoreCase(targetObjectType)) {
                return true;
            }
        }
        return false;
    }
}
