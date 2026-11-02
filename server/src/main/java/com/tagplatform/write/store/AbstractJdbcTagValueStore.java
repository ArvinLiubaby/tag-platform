package com.tagplatform.write.store;

import com.tagplatform.write.model.TagValue;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 共享实现：统一列清单/行映射/查询/流水追加；仅 upsert 语句由子类提供（方言差异）。
 */
public abstract class AbstractJdbcTagValueStore implements TagValueStore {

    protected static final String COLUMNS = "tenant_id, object_type, object_id, tag_code, tag_value, value_type, "
            + "status, biz_time, operate_time, expire_time, operator_id, operator_name, operator_type, operator_org, "
            + "source, confidence, remark, evidence_url, audit_by, audit_time, audit_remark, request_id, op_time, ver, ext_json";

    protected static final RowMapper<TagValue> MAPPER = (rs, rowNum) -> {
        String confidence = rs.getString("confidence");
        long ver = rs.getLong("ver");
        Long verValue = rs.wasNull() ? null : ver;
        return new TagValue(
                rs.getString("tenant_id"),
                rs.getString("object_type"),
                rs.getString("object_id"),
                rs.getString("tag_code"),
                rs.getString("tag_value"),
                rs.getString("value_type"),
                rs.getString("status"),
                toLdt(rs.getTimestamp("biz_time")),
                toLdt(rs.getTimestamp("operate_time")),
                toLdt(rs.getTimestamp("expire_time")),
                rs.getString("operator_id"),
                rs.getString("operator_name"),
                rs.getString("operator_type"),
                rs.getString("operator_org"),
                rs.getString("source"),
                confidence,
                rs.getString("remark"),
                rs.getString("evidence_url"),
                rs.getString("audit_by"),
                toLdt(rs.getTimestamp("audit_time")),
                rs.getString("audit_remark"),
                rs.getString("request_id"),
                toLdt(rs.getTimestamp("op_time")),
                verValue,
                rs.getString("ext_json"));
    };

    protected final JdbcTemplate jdbcTemplate;

    protected AbstractJdbcTagValueStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 方言 upsert 语句（25 个占位符，顺序同 {@link #COLUMNS}） */
    protected abstract String upsertSql();

    @Override
    public void upsertLatest(TagValue value) {
        jdbcTemplate.update(upsertSql(), paramsOf(value));
    }

    @Override
    public void insertHistory(TagValue value) {
        String placeholders = String.join(",", java.util.Collections.nCopies(25, "?"));
        jdbcTemplate.update("INSERT INTO tag_value_history (" + COLUMNS + ") VALUES (" + placeholders + ")",
                paramsOf(value));
    }

    @Override
    public Optional<TagValue> findLatest(String tenantId, String objectType, String objectId, String tagCode) {
        return jdbcTemplate.query("""
                SELECT %s FROM tag_value_latest
                WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND tag_code = ?
                """.formatted(COLUMNS), MAPPER, tenantId, objectType, objectId, tagCode)
                .stream().findFirst();
    }

    @Override
    public List<TagValue> listLatestByObject(String tenantId, String objectType, String objectId) {
        return jdbcTemplate.query("""
                SELECT %s FROM tag_value_latest
                WHERE tenant_id = ? AND object_type = ? AND object_id = ?
                ORDER BY tag_code
                """.formatted(COLUMNS), MAPPER, tenantId, objectType, objectId);
    }

    @Override
    public List<TagValue> listLatestByTag(String tenantId, String tagCode, int limit) {
        int size = Math.max(1, Math.min(limit, 100));
        return jdbcTemplate.query(
                "SELECT " + COLUMNS + " FROM tag_value_latest"
                        + " WHERE tenant_id = ? AND tag_code = ?"
                        + " ORDER BY operate_time DESC LIMIT " + size,
                MAPPER, tenantId, tagCode);
    }

    @Override
    public long countLatestByTag(String tenantId, String tagCode) {
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_value_latest WHERE tenant_id = ? AND tag_code = ?",
                Long.class, tenantId, tagCode);
        return total == null ? 0 : total;
    }

    @Override
    public List<TagValue> listPendingAudit(int limit) {
        return jdbcTemplate.query("""
                SELECT %s FROM tag_value_latest
                WHERE status = 'pending_audit'
                ORDER BY op_time ASC
                LIMIT ?
                """.formatted(COLUMNS), MAPPER, limit);
    }

    @Override
    public List<TagValue> listPendingAudit(String tenantId, int limit) {
        return jdbcTemplate.query("""
                SELECT %s FROM tag_value_latest
                WHERE status = 'pending_audit' AND tenant_id = ?
                ORDER BY op_time ASC
                LIMIT ?
                """.formatted(COLUMNS), MAPPER, tenantId, limit);
    }

    @Override
    public List<TagValue> queryHistory(HistoryQuery query) {
        SqlAndArgs where = buildHistoryWhere(query);
        String sql = "SELECT " + COLUMNS + " FROM tag_value_history" + where.sql()
                + " ORDER BY op_time DESC LIMIT ? OFFSET ?";
        List<Object> args = new ArrayList<>(where.args());
        args.add(query.limit() > 0 ? query.limit() : 20);
        args.add(Math.max(query.offset(), 0));
        return jdbcTemplate.query(sql, MAPPER, args.toArray());
    }

    @Override
    public long countHistory(HistoryQuery query) {
        SqlAndArgs where = buildHistoryWhere(query);
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tag_value_history" + where.sql(),
                Long.class, where.args().toArray());
        return count == null ? 0L : count;
    }

    private SqlAndArgs buildHistoryWhere(HistoryQuery query) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(query.tenantId());
        if (query.operatorId() != null && !query.operatorId().isBlank()) {
            sql.append(" AND operator_id = ?");
            args.add(query.operatorId());
        }
        if (query.objectType() != null && !query.objectType().isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(query.objectType());
        }
        if (query.objectId() != null && !query.objectId().isBlank()) {
            sql.append(" AND object_id = ?");
            args.add(query.objectId());
        }
        if (query.tagCode() != null && !query.tagCode().isBlank()) {
            sql.append(" AND tag_code = ?");
            args.add(query.tagCode());
        }
        if (query.from() != null) {
            sql.append(" AND op_time >= ?");
            args.add(query.from());
        }
        if (query.to() != null) {
            sql.append(" AND op_time <= ?");
            args.add(query.to());
        }
        return new SqlAndArgs(sql.toString(), args);
    }

    private record SqlAndArgs(String sql, List<Object> args) {
    }

    protected static Object[] paramsOf(TagValue v) {
        return new Object[]{
                v.tenantId(), v.objectType(), v.objectId(), v.tagCode(), v.tagValue(), v.valueType(), v.status(),
                v.bizTime(), v.operateTime(), v.expireTime(), v.operatorId(), v.operatorName(), v.operatorType(),
                v.operatorOrg(), v.source(), v.confidence(), v.remark(), v.evidenceUrl(), v.auditBy(), v.auditTime(),
                v.auditRemark(), v.requestId(), v.opTime(), v.ver(), v.extJson()
        };
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
