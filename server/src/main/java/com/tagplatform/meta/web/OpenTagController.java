package com.tagplatform.meta.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.TagOptions;
import com.tagplatform.meta.ext.TagExtModels;
import com.tagplatform.meta.ext.TagExtRepository;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.service.TagMetaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 开放 API · 标签字典查询（GET /api/v1/open/tags）。
 * 返回当前凭证租户可见的 online 标签（公开标签 + 本租户标签）。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenTagController {

    private final TagMetaService tagMetaService;
    private final TagExtRepository tagExtRepository;
    private final ObjectMapper objectMapper;

    public OpenTagController(TagMetaService tagMetaService, TagExtRepository tagExtRepository,
                             ObjectMapper objectMapper) {
        this.tagMetaService = tagMetaService;
        this.tagExtRepository = tagExtRepository;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tags")
    public ApiResponse<Map<String, Object>> tags(
            @RequestParam(value = "category_code", required = false) String categoryCode,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "source_mode", required = false) String sourceMode) {
        String tenantId = RequestContext.getTenantId();
        Map<String, Map<String, String>> extValues = tagExtRepository.allTagValues(tenantId);
        Map<String, String> tagDefaults = tagDefaults(tenantId);
        Map<String, Integer> dimPriorities = dimPriorities(tenantId);
        Map<String, String> categoryNames = categoryNames();
        List<TagView> tags = tagMetaService.listVisibleOnline(tenantId).stream()
                .filter(tag -> categoryCode == null || categoryCode.isBlank()
                        || categoryCode.equals(tag.categoryCode()))
                .filter(tag -> objectType == null || objectType.isBlank()
                        || tag.supportsObjectType(objectType))
                .filter(tag -> sourceMode == null || sourceMode.isBlank()
                        || sourceMode.equalsIgnoreCase(tag.sourceMode()))
                .map(tag -> TagView.from(tag, enumOptions(tag),
                        buildExt(tag, extValues, tagDefaults, dimPriorities),
                        categoryNames,
                        tag.dim1Code() == null ? null : dimPriorities.get(tag.dim1Code())))
                .toList();
        return ApiResponse.ok(Map.of("tags", tags, "total", tags.size()));
    }

    /** 租户内 TAG 级扩展属性的默认值（未单独配置时回落） */
    private Map<String, String> tagDefaults(String tenantId) {
        Map<String, String> defaults = new LinkedHashMap<>();
        for (TagExtModels.AttrDef def : tagExtRepository.listDefs(tenantId, "TAG", true)) {
            String value = def.defaultValue();
            if (value != null && !value.isBlank()) {
                defaults.put(def.attrKey(), value);
            }
        }
        return defaults;
    }

    /** 一级维度 → 展示优先级（DIM1 扩展属性） */
    private Map<String, Integer> dimPriorities(String tenantId) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (TagExtModels.DimPriority dim : tagExtRepository.dimPriorities(tenantId)) {
            if (dim.priority() != null) {
                map.put(dim.dimCode(), dim.priority());
            }
        }
        return map;
    }

    /** 维度编码 → 中文名称（一、二级维度共用 tag_category 一张表） */
    private Map<String, String> categoryNames() {
        Map<String, String> map = new LinkedHashMap<>();
        for (TagMetaRepository.CategoryInfo c : tagMetaService.listCategories()) {
            map.put(c.categoryCode(), c.categoryName());
        }
        return map;
    }

    /**
     * 单个标签的扩展信息：先铺租户默认值，再覆盖该标签实际配置；附带所属一级维度的展示优先级。
     * 值统一为字符串（enum/bool/int 均按存储原样返回），未配置且无默认值的属性不出现。
     */
    private Map<String, String> buildExt(TagMeta tag, Map<String, Map<String, String>> extValues,
                                         Map<String, String> tagDefaults, Map<String, Integer> dimPriorities) {
        Map<String, String> ext = new LinkedHashMap<>(tagDefaults);
        Map<String, String> own = extValues.get(tag.tagCode());
        if (own != null) {
            ext.putAll(own);
        }
        if (tag.dim1Code() != null) {
            Integer dimPriority = dimPriorities.get(tag.dim1Code());
            if (dimPriority != null) {
                ext.put("dim1_priority", String.valueOf(dimPriority));
            }
        }
        ext.values().removeIf(v -> v == null || v.isBlank());
        return ext;
    }

    private List<String> enumOptions(TagMeta tag) {
        return TagOptions.of(tag, objectMapper);
    }

    /**
     * 开放 API 标签视图（契约字段只增不删）。writable=false 的标签不在写入接口可推送范围。
     * valueMultiple=true 时，tag_value 用英文逗号连接多个枚举值（如 "公共道路,港口"）。
     * dim1Name / dim2Name 为维度中文名称（按 tag_category 实时解析，维度被删时为 null）；
     * dim1Priority 为所属一级维度的展示优先级（未配置为 null，与 ext.dim1_priority 同源）。
     */
    public record TagView(
            String tagCode,
            String tagName,
            String categoryCode,
            String dim1Code,
            String dim1Name,
            String dim2Code,
            String dim2Name,
            Integer priority,
            Integer dim1Priority,
            String valueType,
            String unit,
            String valueRange,
            boolean valueMultiple,
            List<String> options,
            String tagType,
            String objectType,
            String sourceMode,
            boolean needValueAudit,
            boolean writable,
            int valueTtlDays,
            String sensitivity,
            String status,
            int version,
            boolean derived,
            java.time.LocalDateTime updatedAt,
            Map<String, String> ext) {

        static TagView from(TagMeta meta, List<String> options, Map<String, String> ext,
                            Map<String, String> categoryNames, Integer dim1Priority) {
            return new TagView(meta.tagCode(), meta.tagName(), meta.categoryCode(),
                    meta.dim1Code(),
                    meta.dim1Code() == null ? null : categoryNames.get(meta.dim1Code()),
                    meta.dim2Code(),
                    meta.dim2Code() == null ? null : categoryNames.get(meta.dim2Code()),
                    meta.priority(),
                    dim1Priority,
                    meta.valueType(),
                    meta.unit(),
                    meta.valueRange(), meta.valueMultiple(), options, meta.tagType(), meta.objectType(),
                    meta.sourceMode(), meta.needValueAudit(), meta.writable(), meta.valueTtlDays(),
                    meta.sensitivity(), meta.status(), meta.version(),
                    "derived".equalsIgnoreCase(meta.tagType()), meta.updatedAt(), ext);
        }
    }
}
