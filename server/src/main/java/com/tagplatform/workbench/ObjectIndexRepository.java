package com.tagplatform.workbench;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对象索引：工作台搜索与任务导入时登记对象首次/最近出现时间。
 */
@Repository
public class ObjectIndexRepository {

    private final JdbcTemplate jdbcTemplate;

    public ObjectIndexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void touch(String tenantId, String objectType, String objectId) {
        LocalDateTime now = LocalDateTime.now();
        int updated = jdbcTemplate.update(
                "UPDATE object_index SET last_seen = ? WHERE tenant_id = ? AND object_type = ? AND object_id = ?",
                now, tenantId, objectType, objectId);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO object_index (tenant_id, object_type, object_id, first_seen, last_seen)
                    VALUES (?, ?, ?, ?, ?)
                    """, tenantId, objectType, objectId, now, now);
        } catch (DuplicateKeyException e) {
            jdbcTemplate.update(
                    "UPDATE object_index SET last_seen = ? WHERE tenant_id = ? AND object_type = ? AND object_id = ?",
                    now, tenantId, objectType, objectId);
        }
    }

    public List<TaskModels.ObjectRow> search(String tenantId, String objectType, String keyword, int limit) {
        return search(tenantId, objectType, keyword, limit, 0);
    }

    public List<TaskModels.ObjectRow> search(String tenantId, String objectType, String keyword,
                                             int limit, int offset) {
        WhereClause where = where(tenantId, objectType, keyword);
        where.args.add(Math.max(1, Math.min(limit, 200)));
        where.args.add(Math.max(offset, 0));
        return jdbcTemplate.query("""
                SELECT tenant_id, object_type, object_id, first_seen, last_seen
                  FROM object_index""" + where.sql + " ORDER BY last_seen DESC LIMIT ? OFFSET ?",
                (rs, rowNum) -> new TaskModels.ObjectRow(
                        rs.getString("tenant_id"), rs.getString("object_type"), rs.getString("object_id"),
                        rs.getTimestamp("first_seen").toLocalDateTime(),
                        rs.getTimestamp("last_seen").toLocalDateTime()), where.args.toArray());
    }

    public long count(String tenantId, String objectType, String keyword) {
        WhereClause where = where(tenantId, objectType, keyword);
        Long total = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM object_index" + where.sql, Long.class, where.args.toArray());
        return total == null ? 0L : total;
    }

    /** “全部对象”任务快照：取该租户+对象类型下对象索引中的全部 ID（limit 用于上限探测） */
    public List<String> listIds(String tenantId, String objectType, int limit) {
        return jdbcTemplate.queryForList("""
                SELECT object_id FROM object_index
                 WHERE tenant_id = ? AND object_type = ?
                 ORDER BY last_seen DESC LIMIT ?
                """, String.class, tenantId, objectType, Math.max(1, limit));
    }

    private WhereClause where(String tenantId, String objectType, String keyword) {
        StringBuilder sql = new StringBuilder(" WHERE tenant_id = ?");
        java.util.List<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (objectType != null && !objectType.isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(objectType.trim());
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND object_id LIKE ?");
            args.add("%" + keyword.trim() + "%");
        }
        return new WhereClause(sql.toString(), args);
    }

    private record WhereClause(String sql, java.util.List<Object> args) {
    }
}
