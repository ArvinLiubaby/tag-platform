package com.tagplatform.query.web.dto;

import com.tagplatform.write.model.TagValue;

import java.util.List;

/**
 * 打标流水分页视图（控制台打标记录页）。
 */
public record HistoryPage(
        int page,
        int size,
        long total,
        List<TagValue> records) {
}
