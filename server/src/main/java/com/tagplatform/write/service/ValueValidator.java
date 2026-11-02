package com.tagplatform.write.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.meta.model.TagMeta;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签值校验：类型匹配 + value_range 约束（{"min":..,"max":..} / {"enum":[...]}）。
 * 返回 null 表示通过；否则返回可直接回给客户端的错误描述。
 */
@Service
public class ValueValidator {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ObjectMapper objectMapper;

    public ValueValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String validate(TagMeta tag, String rawValue) {
        if (rawValue == null || rawValue.isBlank()) {
            return "tag_value 不能为空";
        }
        if (rawValue.length() > 2048) {
            return "tag_value 超长（上限 2048 字符）";
        }
        if (tag.valueMultiple() && !"enum".equals(tag.valueType())) {
            return "仅 enum 标签支持多选";
        }
        return switch (tag.valueType()) {
            case "boolean" -> validateBoolean(rawValue);
            case "number" -> validateNumber(tag, rawValue);
            case "enum" -> validateEnum(tag, rawValue);
            case "date" -> validateDate(rawValue);
            case "string" -> null;
            default -> "标签 value_type 非法: " + tag.valueType();
        };
    }

    /** 多选值归一化：去首尾空格、英文逗号连接；单值/非枚举原样返回 */
    public String normalize(TagMeta tag, String rawValue) {
        if (tag.valueMultiple() && "enum".equals(tag.valueType()) && rawValue != null) {
            return Arrays.stream(rawValue.split(",", -1))
                    .map(String::trim)
                    .collect(Collectors.joining(","));
        }
        return rawValue;
    }

    private String validateBoolean(String rawValue) {
        return "true".equals(rawValue) || "false".equals(rawValue) ? null : "boolean 类型仅接受 true/false";
    }

    private String validateNumber(TagMeta tag, String rawValue) {
        double value;
        try {
            value = Double.parseDouble(rawValue);
        } catch (NumberFormatException e) {
            return "number 类型格式非法: " + rawValue;
        }
        JsonNode range = parseRange(tag.valueRange());
        if (range != null) {
            if (range.hasNonNull("min") && value < range.get("min").asDouble()) {
                return "小于允许最小值 " + range.get("min").asText();
            }
            if (range.hasNonNull("max") && value > range.get("max").asDouble()) {
                return "大于允许最大值 " + range.get("max").asText();
            }
        }
        return null;
    }

    private String validateEnum(TagMeta tag, String rawValue) {
        Set<String> allowed = allowedEnumValues(tag);
        if (allowed.isEmpty()) {
            return "enum 标签未配置允许枚举值";
        }
        if (!tag.valueMultiple()) {
            return allowed.contains(rawValue)
                    ? null : "enum 类型取值不在允许集合内: " + allowed;
        }

        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String part : rawValue.split(",", -1)) {
            String value = part.trim();
            if (value.isEmpty()) {
                return "多选值包含空项（请使用英文逗号分隔）";
            }
            if (!allowed.contains(value)) {
                return "多选值不在允许集合内: " + value;
            }
            if (!selected.add(value)) {
                return "多选值重复: " + value;
            }
        }
        return selected.isEmpty() ? "多选值不能为空" : null;
    }

    private Set<String> allowedEnumValues(TagMeta tag) {
        JsonNode range = parseRange(tag.valueRange());
        if (range == null || !range.has("enum") || !range.get("enum").isArray()) {
            return Set.of();
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonNode item : range.get("enum")) {
            if (item.isTextual()) {
                values.add(item.asText());
            }
        }
        return values;
    }

    private String validateDate(String rawValue) {
        try {
            LocalDateTime.parse(rawValue, DATE_TIME_FORMATTER);
            return null;
        } catch (Exception ignored) {
            // 继续尝试纯日期格式
        }
        try {
            LocalDate.parse(rawValue);
            return null;
        } catch (Exception e) {
            return "date 类型格式非法（支持 yyyy-MM-dd 或 yyyy-MM-dd HH:mm:ss）";
        }
    }

    private JsonNode parseRange(String valueRange) {
        if (valueRange == null || valueRange.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(valueRange);
        } catch (Exception e) {
            return null;
        }
    }
}
