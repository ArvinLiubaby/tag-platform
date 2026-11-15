package com.tagplatform.workbench;

import com.tagplatform.common.ObjectIndexHook;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 对象索引：工作台搜索与任务导入时登记对象首次/最近出现时间；
 * 同时作为开放写入的 ObjectIndexHook 实现，登记对象并维护对象名称（下拉/画像展示用）。
 */
@Repository
public class ObjectIndexRepository implements ObjectIndexHook {

    private final JdbcTemplate jdbcTemplate;

    public ObjectIndexRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void touch(String tenantId, String objectType, String objectId) {
        touch(tenantId, objectType, objectId, null);
    }

    /** 登记/刷新对象索引；objectName 非空时更新名称，空时保留已有值 */
    @Override
    public void touch(String tenantId, String objectType, String objectId, String objectName) {
        LocalDateTime now = LocalDateTime.now();
        boolean hasName = objectName != null && !objectName.isBlank();
        String updateSql = hasName
                ? "UPDATE object_index SET last_seen = ?, object_name = ? WHERE tenant_id = ? AND object_type = ? AND object_id = ?"
                : "UPDATE object_index SET last_seen = ? WHERE tenant_id = ? AND object_type = ? AND object_id = ?";
        int updated = hasName
                ? jdbcTemplate.update(updateSql, now, objectName.trim(), tenantId, objectType, objectId)
                : jdbcTemplate.update(updateSql, now, tenantId, objectType, objectId);
        if (updated > 0) {
            return;
        }
        try {
            jdbcTemplate.update("""
                    INSERT INTO object_index (tenant_id, object_type, object_id, object_name, first_seen, last_seen)
                    VALUES (?, ?, ?, ?, ?, ?)
                    """, tenantId, objectType, objectId, hasName ? objectName.trim() : null, now, now);
        } catch (DuplicateKeyException e) {
            if (hasName) {
                jdbcTemplate.update(updateSql, now, objectName.trim(), tenantId, objectType, objectId);
            } else {
                jdbcTemplate.update(updateSql, now, tenantId, objectType, objectId);
            }
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
