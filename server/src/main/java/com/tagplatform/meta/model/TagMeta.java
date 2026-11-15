package com.tagplatform.meta.model;

import java.time.LocalDateTime;

/**
 * 标签元数据（tag_meta 表映射）。
 *
 * <p>维度说明：dim1Code 为一级维度（tag_category 根节点），dim2Code 为二级维度，
 * 与 categoryCode 同值，显式存储便于按维度过滤与导出。priority 为标签优先级
 * （来源《标签体系清单》「人工打标签标签」列，数值越小越靠前，null 表示非人工打标标签）。
 */
public record TagMeta(
        Long id,
        String tagCode,
        String tagName,
        String categoryCode,
        String dim1Code,
        String dim2Code,
        Integer priority,
        String valueType,
        String unit,
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

    /** 兼容旧构造：不指定单位（unit = null，适用于文本/布尔/枚举类标签） */
    public TagMeta(Long id, String tagCode, String tagName, String categoryCode, String dim1Code,
                   String dim2Code, Integer priority, String valueType, String valueRange,
                   boolean valueMultiple, String tagType, boolean needValueAudit, int valueTtlDays,
                   String tenantId, String sensitivity, String status, int version, String objectType,
                   String sourceMode, boolean writable, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, tagCode, tagName, categoryCode, dim1Code, dim2Code, priority, valueType, null,
                valueRange, valueMultiple, tagType, needValueAudit, valueTtlDays, tenantId, sensitivity,
                status, version, objectType, sourceMode, writable, createdAt, updatedAt);
    }

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
