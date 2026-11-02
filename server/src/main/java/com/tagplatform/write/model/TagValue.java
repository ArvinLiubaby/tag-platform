package com.tagplatform.write.model;

import java.time.LocalDateTime;

/**
 * 标签值（四元素主键：tenant_id + object_type + object_id + tag_code）。
 * 同时作为 tag_value_latest 行模型与 tag_value_history 流水模型。
 */
public record TagValue(
        String tenantId,
        String objectType,
        String objectId,
        String tagCode,
        String tagValue,
        String valueType,
        String status,
        LocalDateTime bizTime,
        LocalDateTime operateTime,
        LocalDateTime expireTime,
        String operatorId,
        String operatorName,
        String operatorType,
        String operatorOrg,
        String source,
        String confidence,
        String remark,
        String evidenceUrl,
        String auditBy,
        LocalDateTime auditTime,
        String auditRemark,
        String requestId,
        LocalDateTime opTime,
        Long ver,
        String extJson) {

    public static final String STATUS_PENDING_AUDIT = "pending_audit";
    public static final String STATUS_EFFECTIVE = "effective";
    public static final String STATUS_EXPIRED = "expired";
    public static final String STATUS_REVOKED = "revoked";

    /** 置信度枚举值（存 tag_value_latest/history 的 confidence 列，VARCHAR(10)） */
    public static final String CONFIDENCE_HIGH = "高";
    public static final String CONFIDENCE_MEDIUM = "中";
    public static final String CONFIDENCE_LOW = "低";
    public static final java.util.Set<String> CONFIDENCE_LEVELS =
            java.util.Set.of(CONFIDENCE_HIGH, CONFIDENCE_MEDIUM, CONFIDENCE_LOW);
}
