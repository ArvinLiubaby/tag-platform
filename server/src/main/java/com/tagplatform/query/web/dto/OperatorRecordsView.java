package com.tagplatform.query.web.dto;

import com.tagplatform.write.model.TagValue;

import java.util.List;

/**
 * 操作员打标记录视图（GET /api/v1/open/operators/{operator_id}/tag-records）。
 */
public record OperatorRecordsView(
        String operatorId,
        int page,
        int size,
        long total,
        List<TagValue> records) {
}
