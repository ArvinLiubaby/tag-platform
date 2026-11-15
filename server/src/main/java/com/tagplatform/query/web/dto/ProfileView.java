package com.tagplatform.query.web.dto;

import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.write.model.TagValue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 对象画像视图：GET /api/v1/open/objects/profiles 的 records[] 元素，也供控制台画像页使用。
 * 已排除 revoked 与已过期值；pending_audit 仅在 include_pending=true 时返回。
 */
public record ProfileView(
        String tenantId,
        String objectType,
        String objectId,
        String objectName,
        List<TagItem> tags) {

    /** 无当前值的占位状态（组合查询 include_empty=true 时返回） */
    public static final String STATUS_NONE = "none";

    /**
     * 维度索引：维度编码 → 中文名称（tag_category）与一级维度展示优先级（DIM1 扩展属性）。
     * 由服务层按租户一次加载、整批复用；TagItem 工厂据此回填维度字段。
     */
    public record DimIndex(Map<String, String> categoryNames, Map<String, Integer> dimPriorities) {

        public static final DimIndex EMPTY = new DimIndex(Map.of(), Map.of());

        public String name(String categoryCode) {
            return categoryCode == null ? null : categoryNames.get(categoryCode);
        }

        public Integer dim1Priority(TagMeta meta) {
            return meta == null || meta.dim1Code() == null ? null : dimPriorities.get(meta.dim1Code());
        }
    }

    public record TagItem(
            String tagCode,
            String tagName,
            String dim1Code,
            String dim1Name,
            String dim2Code,
            String dim2Name,
            Integer priority,
            Integer dim1Priority,
            String tagValue,
            String valueType,
            String unit,
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

        public static TagItem from(TagValue v, TagMeta meta, DimIndex dims) {
            DimIndex d = dims == null ? DimIndex.EMPTY : dims;
            return new TagItem(v.tagCode(), meta == null ? null : meta.tagName(),
                    meta == null ? null : meta.dim1Code(), d.name(meta == null ? null : meta.dim1Code()),
                    meta == null ? null : meta.dim2Code(), d.name(meta == null ? null : meta.dim2Code()),
                    meta == null ? null : meta.priority(), d.dim1Priority(meta),
                    v.tagValue(), v.valueType(),
                    meta == null ? null : meta.unit(),
                    meta != null && meta.valueMultiple(), v.status(),
                    meta == null ? null : meta.sourceMode(), meta != null && meta.needValueAudit(),
                    v.bizTime(), v.operateTime(), v.expireTime(),
                    v.operatorId(), v.operatorName(), v.operatorType(), v.operatorOrg(),
                    v.source(), v.confidence(), v.remark(), v.evidenceUrl(), false);
        }

        /** 无当前值占位：值相关字段为 null，字典元数据（名称/来源/敏感审核）取自标签定义 */
        public static TagItem none(TagMeta meta, DimIndex dims) {
            DimIndex d = dims == null ? DimIndex.EMPTY : dims;
            return new TagItem(meta.tagCode(), meta.tagName(),
                    meta.dim1Code(), d.name(meta.dim1Code()),
                    meta.dim2Code(), d.name(meta.dim2Code()),
                    meta.priority(), d.dim1Priority(meta),
                    null, meta.valueType(), meta.unit(),
                    meta.valueMultiple(),
                    STATUS_NONE, meta.sourceMode(), meta.needValueAudit(),
                    null, null, null, null, null, null, null, null, null, null, null, false);
        }

        /** 派生标签（查询期实时计算）：无流水与操作人，标注 derived=true 供前端区分 */
        public static TagItem fromDerived(TagMeta meta, String value, DimIndex dims) {
            DimIndex d = dims == null ? DimIndex.EMPTY : dims;
            return new TagItem(meta.tagCode(), meta.tagName(),
                    meta.dim1Code(), d.name(meta.dim1Code()),
                    meta.dim2Code(), d.name(meta.dim2Code()),
                    meta.priority(), d.dim1Priority(meta),
                    value, meta.valueType(), meta.unit(), false,
                    value == null ? STATUS_NONE : "effective", "derived", false,
                    null, null, null, null, null, null, null, "derived", null, null, null, true);
        }
    }
}
