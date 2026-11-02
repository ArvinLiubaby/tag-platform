package com.tagplatform.analytics;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 控制台数据看板聚合结果。
 */
public record DashboardOverview(
        int days,
        String from,
        String to,
        Kpi kpi,
        List<TrendPoint> trend,
        List<EndpointStat> endpoints,
        List<TenantStat> tenants,
        List<TagStat> topWriteTags,
        List<TagStat> topQueryTags,
        List<CallRow> recentCalls) {

    public record Kpi(
            long totalCalls,
            long totalWrites,
            long totalQueries,
            long totalDeletes,
            long activeTenants,
            long activeApps,
            double successRate,
            long avgLatencyMs) {
    }

    public record TrendPoint(String label, long calls, long writes, long queries) {
    }

    public record EndpointStat(String method, String endpoint, long calls, double successRate, long avgLatencyMs) {
    }

    public record TenantStat(String tenantId, String tenantName, long calls, long writes, long queries) {
    }

    public record TagStat(String tagCode, String tagName, long count) {
    }

    public record CallRow(
            LocalDateTime calledAt,
            String tenantId,
            String appKey,
            String method,
            String endpoint,
            int httpStatus,
            Integer bizCode,
            boolean success,
            long durationMs,
            String operatorId,
            long writeCount,
            long queryCount,
            long deleteCount) {
    }
}
