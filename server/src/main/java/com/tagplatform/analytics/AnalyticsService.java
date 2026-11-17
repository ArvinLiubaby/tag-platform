package com.tagplatform.analytics;

import com.tagplatform.analytics.DashboardOverview.CallRow;
import com.tagplatform.analytics.DashboardOverview.EndpointStat;
import com.tagplatform.analytics.DashboardOverview.Kpi;
import com.tagplatform.analytics.DashboardOverview.TagStat;
import com.tagplatform.analytics.DashboardOverview.TenantStat;
import com.tagplatform.analytics.DashboardOverview.TrendPoint;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;

import java.sql.Date;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 分析埋点写入与看板聚合。
 * 埋点写入失败不影响业务请求（由过滤器捕获并仅记录日志）。
 */
@Service
public class AnalyticsService {

    private static final DateTimeFormatter MM_DD = DateTimeFormatter.ofPattern("MM-dd");

    private static final String INSERT_CALL = """
            INSERT INTO api_call_log (tenant_id, app_key, method, endpoint, http_status, biz_code, success,
              duration_ms, write_count, query_count, delete_count, operator_id, object_type, object_id,
              request_id, client_ip, called_at)
            VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
            """;

    private static final String UPDATE_TAG_ACCESS = """
            UPDATE tag_access_daily
               SET access_count = access_count + ?, last_access_at = ?
             WHERE stat_date = ? AND tenant_id = ? AND tag_code = ? AND action = ?
            """;

    private static final String INSERT_TAG_ACCESS = """
            INSERT INTO tag_access_daily (stat_date, tenant_id, tag_code, action, access_count, last_access_at)
            VALUES (?,?,?,?,?,?)
            """;

    private static final String KPI_SQL = """
            SELECT COUNT(*)                                                     AS calls,
                   COALESCE(SUM(write_count), 0)                                AS writes,
                   COALESCE(SUM(query_count), 0)                                AS queries,
                   COALESCE(SUM(delete_count), 0)                               AS deletes,
                   COUNT(DISTINCT tenant_id)                                    AS active_tenants,
                   COUNT(DISTINCT app_key)                                      AS active_apps,
                   COALESCE(SUM(success), 0)                                    AS success_calls,
                   COALESCE(AVG(duration_ms), 0)                                AS avg_ms
              FROM api_call_log
             WHERE called_at >= ?%s
            """;

    private static final String TREND_HOUR_SQL = """
            SELECT HOUR(called_at)                   AS h,
                   COUNT(*)                          AS calls,
                   COALESCE(SUM(write_count), 0)     AS writes,
                   COALESCE(SUM(query_count), 0)     AS queries
              FROM api_call_log
             WHERE called_at >= ?%s
             GROUP BY HOUR(called_at)
             ORDER BY h
            """;

    private static final String TREND_DATE_SQL = """
            SELECT DATE(called_at)                   AS d,
                   COUNT(*)                          AS calls,
                   COALESCE(SUM(write_count), 0)     AS writes,
                   COALESCE(SUM(query_count), 0)     AS queries
              FROM api_call_log
             WHERE called_at >= ?%s
             GROUP BY DATE(called_at)
             ORDER BY d
            """;

    private static final String ENDPOINT_SQL = """
            SELECT method,
                   endpoint,
                   COUNT(*)                                  AS calls,
                   SUM(success) * 1.0 / COUNT(*)             AS success_rate,
                   COALESCE(AVG(duration_ms), 0)             AS avg_ms
              FROM api_call_log
             WHERE called_at >= ?%s
             GROUP BY method, endpoint
             ORDER BY calls DESC
             LIMIT ?
            """;

    private static final String TENANT_SQL = """
            SELECT COALESCE(l.tenant_id, 'unknown')                          AS tenant_id,
                   COALESCE(t.tenant_name, l.tenant_id, '未鉴权调用')          AS tenant_name,
                   COUNT(*)                                                 AS calls,
                   COALESCE(SUM(l.write_count), 0)                          AS writes,
                   COALESCE(SUM(l.query_count), 0)                          AS queries
              FROM api_call_log l
              LEFT JOIN tenant t ON t.tenant_id = l.tenant_id
             WHERE l.called_at >= ?%s
             GROUP BY l.tenant_id, t.tenant_name
             ORDER BY calls DESC
             LIMIT ?
            """;

    private static final String TOP_TAG_SQL = """
            SELECT s.tag_code                                   AS tag_code,
                   COALESCE(m.tag_name, s.tag_code)             AS tag_name,
                   SUM(s.access_count)                          AS cnt
              FROM tag_access_daily s
              LEFT JOIN tag_meta m ON m.tag_code = s.tag_code
             WHERE s.stat_date >= ? AND s.action = ?%s
             GROUP BY s.tag_code, m.tag_name
             ORDER BY cnt DESC
             LIMIT ?
            """;

    private static final String RECENT_SQL = """
            SELECT called_at, tenant_id, app_key, method, endpoint, http_status, biz_code, success,
                   duration_ms, operator_id, write_count, query_count, delete_count
              FROM api_call_log
             WHERE 1 = 1%s
             ORDER BY called_at DESC, id DESC
             LIMIT ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public AnalyticsService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ write

    public void recordCall(ApiCallRecord r) {
        jdbcTemplate.update(INSERT_CALL,
                r.tenantId(), r.appKey(), r.method(), r.endpoint(), r.httpStatus(), r.bizCode(),
                r.success() ? 1 : 0, r.durationMs(),
                total(r.writes()), total(r.queries()), total(r.deletes()),
                r.operatorId(), r.objectType(), r.objectId(), r.requestId(), r.clientIp(), r.calledAt());
        if (r.tenantId() == null || r.tenantId().isBlank()) {
            return;
        }
        Date statDate = Date.valueOf(r.calledAt().toLocalDate());
        upsertTagAccesses(statDate, r.tenantId(), "query", r.queries(), r.calledAt());
        upsertTagAccesses(statDate, r.tenantId(), "write", r.writes(), r.calledAt());
        upsertTagAccesses(statDate, r.tenantId(), "delete", r.deletes(), r.calledAt());
    }

    private void upsertTagAccesses(Date statDate, String tenantId, String action,
                                   Map<String, Integer> tags, LocalDateTime now) {
        for (Map.Entry<String, Integer> entry : tags.entrySet()) {
            int updated = jdbcTemplate.update(UPDATE_TAG_ACCESS,
                    entry.getValue(), now, statDate, tenantId, entry.getKey(), action);
            if (updated > 0) {
                continue;
            }
            try {
                jdbcTemplate.update(INSERT_TAG_ACCESS, statDate, tenantId, entry.getKey(), action,
                        entry.getValue(), now);
            } catch (DuplicateKeyException e) {
                jdbcTemplate.update(UPDATE_TAG_ACCESS,
                        entry.getValue(), now, statDate, tenantId, entry.getKey(), action);
            }
        }
    }

    private static int total(Map<String, Integer> values) {
        return values == null ? 0 : values.values().stream().mapToInt(Integer::intValue).sum();
    }

    // ------------------------------------------------------------------ dashboard

    public DashboardOverview overview(int days) {
        return overview(days, null);
    }

    /**
     * @param tenantId 为空表示全租户（仅平台管理员）；非空时所有指标只统计该租户
     */
    public DashboardOverview overview(int days, String tenantId) {
        int d = (days == 1 || days == 7 || days == 30) ? days : 7;
        LocalDate end = LocalDate.now();
        LocalDate startDate = d == 1 ? end : end.minusDays(d - 1);
        LocalDateTime start = startDate.atStartOfDay();
        String scope = tenantId == null || tenantId.isBlank() ? null : tenantId.trim();

        Kpi kpi = loadKpi(start, scope);
        List<TrendPoint> trend = loadTrend(d, startDate, start, scope);
        List<EndpointStat> endpoints = loadEndpoints(start, scope);
        List<TenantStat> tenants = loadTenants(start, scope);
        List<TagStat> topWrite = loadTopTags(startDate, "write", scope);
        List<TagStat> topQuery = loadTopTags(startDate, "query", scope);
        List<CallRow> recent = loadRecent(scope);
        return new DashboardOverview(d, startDate.toString(), end.toString(), kpi, trend, endpoints, tenants,
                topWrite, topQuery, recent);
    }

    private Kpi loadKpi(LocalDateTime start, String tenantId) {
        String sql = KPI_SQL.formatted(tenantId == null ? "" : " AND tenant_id = ?");
        Kpi kpi = tenantId == null
                ? jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toKpi(rs), start)
                : jdbcTemplate.queryForObject(sql, (rs, rowNum) -> toKpi(rs), start, tenantId);
        return kpi == null ? new Kpi(0, 0, 0, 0, 0, 0, 0, 0) : kpi;
    }

    private static Kpi toKpi(java.sql.ResultSet rs) throws java.sql.SQLException {
        long calls = rs.getLong("calls");
        long successCalls = rs.getLong("success_calls");
        double rate = calls == 0 ? 0.0 : round4(successCalls * 1.0 / calls);
        return new Kpi(calls, rs.getLong("writes"), rs.getLong("queries"), rs.getLong("deletes"),
                rs.getLong("active_tenants"), rs.getLong("active_apps"), rate,
                Math.round(rs.getDouble("avg_ms")));
    }

    private List<TrendPoint> loadTrend(int days, LocalDate startDate, LocalDateTime start, String tenantId) {
        if (days == 1) {
            Map<Integer, TrendPoint> points = new TreeMap<>();
            int currentHour = LocalDateTime.now().getHour();
            for (int h = 0; h <= currentHour; h++) {
                points.put(h, new TrendPoint(hourLabel(h), 0, 0, 0));
            }
            RowCallbackHandler handler = rs -> {
                int h = rs.getInt("h");
                points.put(h, new TrendPoint(hourLabel(h), rs.getLong("calls"),
                        rs.getLong("writes"), rs.getLong("queries")));
            };
            String sql = TREND_HOUR_SQL.formatted(tenantId == null ? "" : " AND tenant_id = ?");
            if (tenantId == null) {
                jdbcTemplate.query(sql, handler, start);
            } else {
                jdbcTemplate.query(sql, handler, start, tenantId);
            }
            return new ArrayList<>(points.values());
        }

        Map<LocalDate, TrendPoint> points = new TreeMap<>();
        for (LocalDate date = startDate; !date.isAfter(LocalDate.now()); date = date.plusDays(1)) {
            points.put(date, new TrendPoint(date.format(MM_DD), 0, 0, 0));
        }
        RowCallbackHandler handler = rs -> {
            LocalDate date = rs.getDate("d").toLocalDate();
            points.put(date, new TrendPoint(date.format(MM_DD), rs.getLong("calls"),
                    rs.getLong("writes"), rs.getLong("queries")));
        };
        String sql = TREND_DATE_SQL.formatted(tenantId == null ? "" : " AND tenant_id = ?");
        if (tenantId == null) {
            jdbcTemplate.query(sql, handler, start);
        } else {
            jdbcTemplate.query(sql, handler, start, tenantId);
        }
        return new ArrayList<>(points.values());
    }

    private List<EndpointStat> loadEndpoints(LocalDateTime start, String tenantId) {
        String sql = ENDPOINT_SQL.formatted(tenantId == null ? "" : " AND tenant_id = ?");
        return tenantId == null
                ? jdbcTemplate.query(sql, (rs, rowNum) -> new EndpointStat(
                        rs.getString("method"), rs.getString("endpoint"), rs.getLong("calls"),
                        round4(rs.getDouble("success_rate")), Math.round(rs.getDouble("avg_ms"))), start, 10)
                : jdbcTemplate.query(sql, (rs, rowNum) -> new EndpointStat(
                        rs.getString("method"), rs.getString("endpoint"), rs.getLong("calls"),
                        round4(rs.getDouble("success_rate")), Math.round(rs.getDouble("avg_ms"))),
                        start, tenantId, 10);
    }

    private List<TenantStat> loadTenants(LocalDateTime start, String tenantId) {
        String sql = TENANT_SQL.formatted(tenantId == null ? "" : " AND l.tenant_id = ?");
        return tenantId == null
                ? jdbcTemplate.query(sql, (rs, rowNum) -> new TenantStat(
                        rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getLong("calls"),
                        rs.getLong("writes"), rs.getLong("queries")), start, 8)
                : jdbcTemplate.query(sql, (rs, rowNum) -> new TenantStat(
                        rs.getString("tenant_id"), rs.getString("tenant_name"), rs.getLong("calls"),
                        rs.getLong("writes"), rs.getLong("queries")), start, tenantId, 8);
    }

    private List<TagStat> loadTopTags(LocalDate startDate, String action, String tenantId) {
        String sql = TOP_TAG_SQL.formatted(tenantId == null ? "" : " AND s.tenant_id = ?");
        return tenantId == null
                ? jdbcTemplate.query(sql, (rs, rowNum) -> new TagStat(
                        rs.getString("tag_code"), rs.getString("tag_name"), rs.getLong("cnt")),
                        Date.valueOf(startDate), action, 8)
                : jdbcTemplate.query(sql, (rs, rowNum) -> new TagStat(
                        rs.getString("tag_code"), rs.getString("tag_name"), rs.getLong("cnt")),
                        Date.valueOf(startDate), action, tenantId, 8);
    }

    private List<CallRow> loadRecent(String tenantId) {
        String sql = RECENT_SQL.formatted(tenantId == null ? "" : " AND tenant_id = ?");
        return tenantId == null
                ? jdbcTemplate.query(sql, (rs, rowNum) -> toCallRow(rs), 15)
                : jdbcTemplate.query(sql, (rs, rowNum) -> toCallRow(rs), tenantId, 15);
    }

    private static CallRow toCallRow(java.sql.ResultSet rs) throws java.sql.SQLException {
        Object bizCodeValue = rs.getObject("biz_code");
        Integer bizCode = bizCodeValue == null ? null : ((Number) bizCodeValue).intValue();
        return new CallRow(
                rs.getTimestamp("called_at").toLocalDateTime(),
                rs.getString("tenant_id"), rs.getString("app_key"), rs.getString("method"),
                rs.getString("endpoint"), rs.getInt("http_status"), bizCode,
                rs.getInt("success") == 1, rs.getLong("duration_ms"), rs.getString("operator_id"),
                rs.getLong("write_count"), rs.getLong("query_count"), rs.getLong("delete_count"));
    }

    private static String hourLabel(int hour) {
        return String.format("%02d:00", hour);
    }

    private static double round4(double value) {
        return Math.round(value * 10000.0) / 10000.0;
    }
}
