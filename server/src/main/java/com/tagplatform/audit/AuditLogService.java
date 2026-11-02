package com.tagplatform.audit;

import com.tagplatform.common.RequestContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 审计日志服务（audit_log 表，保留 3 年）：
 *   actor 自动推断：控制台用户（username/role）或开放 API 凭证（appKey/APP）。
 */
@Service
public class AuditLogService {

    private static final int MAX_DETAIL = 2000;

    private static final RowMapper<AuditLogInfo> MAPPER = (rs, rowNum) -> new AuditLogInfo(
            rs.getLong("id"),
            rs.getString("request_id"),
            rs.getString("actor"),
            rs.getString("actor_role"),
            rs.getString("action_type"),
            rs.getString("tenant_id"),
            rs.getString("object_type"),
            rs.getString("object_id"),
            rs.getString("tag_code"),
            rs.getString("detail"),
            rs.getString("result"),
            rs.getString("source_ip"),
            toLdt(rs.getTimestamp("created_at")));

    private final JdbcTemplate jdbcTemplate;

    public AuditLogService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void record(String actionType, String tenantId, String objectType, String objectId,
                       String tagCode, String detail, String result) {
        String actor = RequestContext.getConsoleUsername();
        String actorRole = RequestContext.getConsoleRole();
        if (actor == null) {
            String appKey = RequestContext.getAppKey();
            actor = appKey != null ? appKey : "anonymous";
            actorRole = appKey != null ? "APP" : null;
        }
        jdbcTemplate.update("""
                INSERT INTO audit_log (request_id, actor, actor_role, action_type, tenant_id, object_type,
                                       object_id, tag_code, detail, result, source_ip, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                RequestContext.getRequestId(), actor, actorRole, actionType, tenantId, objectType,
                objectId, tagCode, truncate(detail), result, RequestContext.getSourceIp(), LocalDateTime.now());
    }

    public AuditPage list(String actionType, String tenantId, LocalDateTime from, LocalDateTime to,
                          int page, int size) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (actionType != null && !actionType.isBlank()) {
            where.append(" AND action_type = ?");
            args.add(actionType);
        }
        if (tenantId != null && !tenantId.isBlank()) {
            where.append(" AND tenant_id = ?");
            args.add(tenantId);
        }
        if (from != null) {
            where.append(" AND created_at >= ?");
            args.add(from);
        }
        if (to != null) {
            where.append(" AND created_at <= ?");
            args.add(to);
        }
        int normalizedPage = Math.max(page, 1);
        int normalizedSize = Math.min(Math.max(size, 1), 200);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM audit_log" + where,
                Long.class, args.toArray());
        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(normalizedSize);
        pageArgs.add((normalizedPage - 1) * normalizedSize);
        List<AuditLogInfo> records = jdbcTemplate.query(
                "SELECT * FROM audit_log" + where + " ORDER BY created_at DESC LIMIT ? OFFSET ?",
                MAPPER, pageArgs.toArray());
        return new AuditPage(normalizedPage, normalizedSize, total == null ? 0L : total, records);
    }

    private String truncate(String detail) {
        if (detail == null) {
            return null;
        }
        return detail.length() <= MAX_DETAIL ? detail : detail.substring(0, MAX_DETAIL);
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }

    /**
     * 审计日志视图。
     */
    public record AuditLogInfo(
            Long id,
            String requestId,
            String actor,
            String actorRole,
            String actionType,
            String tenantId,
            String objectType,
            String objectId,
            String tagCode,
            String detail,
            String result,
            String sourceIp,
            LocalDateTime createdAt) {
    }

    public record AuditPage(int page, int size, long total, List<AuditLogInfo> records) {
    }
}
