package com.tagplatform.meta.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.service.TagMetaService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
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
    private final ObjectMapper objectMapper;

    public OpenTagController(TagMetaService tagMetaService, ObjectMapper objectMapper) {
        this.tagMetaService = tagMetaService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/tags")
    public ApiResponse<Map<String, Object>> tags(
            @RequestParam(value = "category_code", required = false) String categoryCode,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "source_mode", required = false) String sourceMode) {
        String tenantId = RequestContext.getTenantId();
        List<TagView> tags = tagMetaService.listVisibleOnline(tenantId).stream()
                .filter(tag -> categoryCode == null || categoryCode.isBlank()
                        || categoryCode.equals(tag.categoryCode()))
                .filter(tag -> objectType == null || objectType.isBlank()
                        || tag.supportsObjectType(objectType))
                .filter(tag -> sourceMode == null || sourceMode.isBlank()
                        || sourceMode.equalsIgnoreCase(tag.sourceMode()))
                .map(tag -> TagView.from(tag, enumOptions(tag)))
                .toList();
        return ApiResponse.ok(Map.of("tags", tags, "total", tags.size()));
    }

    private List<String> enumOptions(TagMeta tag) {
        if (!"enum".equals(tag.valueType())) {
            return List.of();
        }
        try {
            JsonNode root = objectMapper.readTree(tag.valueRange());
            JsonNode enumNode = root == null ? null : root.get("enum");
            if (enumNode == null || !enumNode.isArray()) {
                return List.of();
            }
            List<String> options = new ArrayList<>();
            for (JsonNode node : enumNode) {
                if (node.isTextual()) {
                    options.add(node.asText());
                }
            }
            return options;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * 开放 API 标签视图（契约字段只增不删）。writable=false 的标签不在写入接口可推送范围。
     * valueMultiple=true 时，tag_value 用英文逗号连接多个枚举值（如 "公共道路,港口"）。
     */
    public record TagView(
            String tagCode,
            String tagName,
            String categoryCode,
            String valueType,
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
            java.time.LocalDateTime updatedAt) {

        static TagView from(TagMeta meta, List<String> options) {
            return new TagView(meta.tagCode(), meta.tagName(), meta.categoryCode(), meta.valueType(),
                    meta.valueRange(), meta.valueMultiple(), options, meta.tagType(), meta.objectType(),
                    meta.sourceMode(), meta.needValueAudit(), meta.writable(), meta.valueTtlDays(),
                    meta.sensitivity(), meta.status(), meta.version(),
                    "derived".equalsIgnoreCase(meta.tagType()), meta.updatedAt());
        }
    }
}
