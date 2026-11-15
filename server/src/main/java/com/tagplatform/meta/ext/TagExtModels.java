package com.tagplatform.meta.ext;

import java.util.List;
import java.util.Map;

/**
 * 租户级标签扩展信息：属性定义 + 属性值。
 *
 * <p>设计意图：扩展属性随业务增长可增减，不在 tag_meta 上硬编码列。
 * subject_type = TAG 时 subject_code 为 tag_code；= DIM1 时为一级维度编码。
 */
public final class TagExtModels {

    private TagExtModels() {
    }

    /** 属性定义（tag_ext_attr_def） */
    public record AttrDef(
            Long id,
            String tenantId,
            String attrKey,
            String attrName,
            String subjectType,
            String valueType,
            String optionsJson,
            String defaultValue,
            boolean editable,
            boolean visible,
            int sortNo,
            String status) {
    }

    /** 标签扩展视图：定义 + 生效值（未配置时回落默认值，inherited=true） */
    public record AttrView(
            String attrKey,
            String attrName,
            String subjectType,
            String valueType,
            String optionsJson,
            String defaultValue,
            boolean editable,
            boolean visible,
            int sortNo,
            String value,
            boolean inherited) {
    }

    public record TagExtView(String tenantId, String tagCode, List<AttrView> attrs) {
    }

    /** 保存标签扩展值：key → value，空值表示清除（回落默认值） */
    public record SaveExtRequest(Map<String, String> values) {
    }

    public record DefRequest(
            String attrKey,
            String attrName,
            String subjectType,
            String valueType,
            String optionsJson,
            String defaultValue,
            Boolean editable,
            Boolean visible,
            Integer sortNo) {
    }

    public record StatusRequest(String status) {
    }

    /** 一级维度优先级视图 */
    public record DimPriority(String dimCode, String dimName, Integer priority) {
    }

    public record DimPriorityRequest(List<DimPriority> items) {
    }
}
