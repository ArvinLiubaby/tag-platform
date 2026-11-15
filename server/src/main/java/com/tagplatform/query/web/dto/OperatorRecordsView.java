package com.tagplatform.query.web.dto;

import com.tagplatform.write.model.TagValue;

import java.util.List;
import java.util.Map;

/**
 * 操作员打标记录视图（GET /api/v1/open/tag-records，旧路径式 /operators/{operator_id}/tag-records 同构兼容）。
 */
public record OperatorRecordsView(
        String operatorId,
        int page,
        int size,
        long total,
        List<TagValue> records,
        Map<String, String> units) {
}
