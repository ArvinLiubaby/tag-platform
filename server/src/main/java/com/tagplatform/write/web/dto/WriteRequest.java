package com.tagplatform.write.web.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 开放 API · 标签值写入请求（POST /api/v1/open/tag-values/write）。
 * 批量上限见 tag-platform.limits.batch-max（默认 1000）；整批校验全部通过才受理。
 */
public record WriteRequest(
        String bizRequestId,
        List<WriteItem> items) {

    public record WriteItem(
            String objectType,
            String objectId,
            String tagCode,
            String tagValue,
            LocalDateTime bizTime,
            LocalDateTime operateTime,
            OperatorDto operator,
            String source,
            String confidence,
            String remark,
            String evidenceUrl,
            String extJson) {
    }
}
