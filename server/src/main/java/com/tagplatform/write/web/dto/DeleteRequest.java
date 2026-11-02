package com.tagplatform.write.web.dto;

import java.util.List;

/**
 * 开放 API · 标签值作废请求（POST /api/v1/open/tag-values/delete）。
 * 逐条处理：不存在 → not_found；已作废 → 幂等返回 revoked。
 */
public record DeleteRequest(
        String bizRequestId,
        List<DeleteItem> items) {

    public record DeleteItem(
            String objectType,
            String objectId,
            String tagCode,
            OperatorDto operator,
            String remark) {
    }
}
