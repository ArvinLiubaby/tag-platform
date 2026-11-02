package com.tagplatform.meta.model;

import java.time.LocalDateTime;

/**
 * 标签定义版本快照（tag_meta_version 表映射）。
 * 只保存定义字段，不保存上下线/归档等生命周期状态；回滚不改变当前状态。
 */
public record TagVersion(
        Long id,
        String tagCode,
        int version,
        String changeType,
        String changeBy,
        String changeSummary,
        Definition definition,
        String compositionJson,
        LocalDateTime createdAt) {

    public Summary summary() {
        return new Summary(version, changeType, changeBy, changeSummary, createdAt);
    }

    public record Summary(
            int version,
            String changeType,
            String changeBy,
            String changeSummary,
            LocalDateTime createdAt) {
    }

    public record Detail(Summary version, Definition definition, Definition previous) {
    }

    public record Definition(
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
            String objectType,
            String sourceMode,
            boolean writable) {

        public static Definition from(TagMeta meta) {
            return new Definition(meta.tagName(), meta.categoryCode(), meta.valueType(), meta.valueRange(),
                    meta.valueMultiple(), meta.tagType(), meta.needValueAudit(), meta.valueTtlDays(),
                    meta.tenantId(), meta.sensitivity(), meta.objectType(), meta.sourceMode(), meta.writable());
        }
    }
}
