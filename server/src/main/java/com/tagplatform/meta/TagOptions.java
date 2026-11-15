package com.tagplatform.meta;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.meta.model.TagMeta;

import java.util.ArrayList;
import java.util.List;

/**
 * 标签枚举值解析：从 value_range 中取 enum 数组，供字典接口与任务待办清单复用。
 */
public final class TagOptions {

    private TagOptions() {
    }

    public static List<String> of(TagMeta tag, ObjectMapper mapper) {
        if (tag == null || !"enum".equals(tag.valueType()) || tag.valueRange() == null) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(tag.valueRange());
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
}
