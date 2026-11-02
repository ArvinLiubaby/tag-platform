package com.tagplatform.analytics;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 一次开放 API 调用的埋点快照（由 ApiCallAnalyticsFilter 组装）。
 */
public record ApiCallRecord(
        String tenantId,
        String appKey,
        String method,
        String endpoint,
        int httpStatus,
        Integer bizCode,
        boolean success,
        int durationMs,
        String operatorId,
        String objectType,
        String objectId,
        String requestId,
        String clientIp,
        LocalDateTime calledAt,
        Map<String, Integer> queries,
        Map<String, Integer> writes,
        Map<String, Integer> deletes) {
}
