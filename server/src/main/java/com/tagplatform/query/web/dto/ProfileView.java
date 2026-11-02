package com.tagplatform.query.web.dto;

import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.write.model.TagValue;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对象画像视图（GET /api/v1/open/objects/{object_id}/profile）。
 * 已排除 revoked 与已过期值；pending_audit 仅在 include_pending=true 时返回。
 */
public record ProfileView(
        String tenantId,
        String objectType,
        String objectId,
        List<TagItem> tags) {

    /** 无当前值的占位状态（组合查询 include_empty=true 时返回） */
    public static final String STATUS_NONE = "none";

    public record TagItem(
            String tagCode,
            String tagName,
            String tagValue,
            String valueType,
            boolean valueMultiple,
            String status,
            String sourceMode,
            boolean needValueAudit,
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
            boolean derived) {

        public static TagItem from(TagValue v, TagMeta meta) {
            return new TagItem(v.tagCode(), meta == null ? null : meta.tagName(), v.tagValue(), v.valueType(),
                    meta != null && meta.valueMultiple(), v.status(),
                    meta == null ? null : meta.sourceMode(), meta != null && meta.needValueAudit(),
                    v.bizTime(), v.operateTime(), v.expireTime(),
                    v.operatorId(), v.operatorName(), v.operatorType(), v.operatorOrg(),
                    v.source(), v.confidence(), v.remark(), v.evidenceUrl(), false);
        }

        /** 无当前值占位：值相关字段为 null，字典元数据（名称/来源/敏感审核）取自标签定义 */
        public static TagItem none(TagMeta meta) {
            return new TagItem(meta.tagCode(), meta.tagName(), null, meta.valueType(), meta.valueMultiple(),
                    STATUS_NONE, meta.sourceMode(), meta.needValueAudit(),
                    null, null, null, null, null, null, null, null, null, null, null, false);
        }

        /** 派生标签（查询期实时计算）：无流水与操作人，标注 derived=true 供前端区分 */
        public static TagItem fromDerived(TagMeta meta, String value) {
            return new TagItem(meta.tagCode(), meta.tagName(), value, meta.valueType(), false,
                    value == null ? STATUS_NONE : "effective", "derived", false,
                    null, null, null, null, null, null, null, "derived", null, null, null, true);
        }
    }
}
