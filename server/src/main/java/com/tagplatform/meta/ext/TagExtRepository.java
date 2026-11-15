package com.tagplatform.meta.ext;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 标签扩展信息仓储：属性定义与属性值（租户隔离）。
 */
@Repository
public class TagExtRepository {

    private static final RowMapper<TagExtModels.AttrDef> DEF_MAPPER = (rs, i) -> new TagExtModels.AttrDef(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("attr_key"),
            rs.getString("attr_name"),
            rs.getString("subject_type"),
            rs.getString("value_type"),
            rs.getString("options_json"),
            rs.getString("default_value"),
            rs.getInt("editable") == 1,
            rs.getInt("visible") == 1,
            rs.getInt("sort_no"),
            rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public TagExtRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TagExtModels.AttrDef> listDefs(String tenantId, String subjectType, boolean onlyEnabled) {
        StringBuilder sql = new StringBuilder(
                "SELECT * FROM tag_ext_attr_def WHERE tenant_id = ?");
        if (subjectType != null && !subjectType.isBlank()) {
            sql.append(" AND subject_type = ?");
        }
        if (onlyEnabled) {
            sql.append(" AND status = 'enabled'");
        }
        sql.append(" ORDER BY sort_no, attr_key");
        if (subjectType != null && !subjectType.isBlank()) {
            return jdbcTemplate.query(sql.toString(), DEF_MAPPER, tenantId, subjectType);
        }
        return jdbcTemplate.query(sql.toString(), DEF_MAPPER, tenantId);
    }

    public Optional<TagExtModels.AttrDef> findDef(String tenantId, String attrKey) {
        return jdbcTemplate.query("SELECT * FROM tag_ext_attr_def WHERE tenant_id = ? AND attr_key = ?",
                DEF_MAPPER, tenantId, attrKey).stream().findFirst();
    }

    public void insertDef(TagExtModels.AttrDef d) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO tag_ext_attr_def (tenant_id, attr_key, attr_name, subject_type, value_type,
                    options_json, default_value, editable, visible, sort_no, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                d.tenantId(), d.attrKey(), d.attrName(), d.subjectType(), d.valueType(), d.optionsJson(),
                d.defaultValue(), d.editable() ? 1 : 0, d.visible() ? 1 : 0, d.sortNo(), d.status(), now, now);
    }

    public int updateDef(String tenantId, String attrKey, String attrName, String valueType,
                         String optionsJson, String defaultValue, boolean editable, boolean visible, int sortNo) {
        return jdbcTemplate.update("""
                UPDATE tag_ext_attr_def SET attr_name = ?, value_type = ?, options_json = ?, default_value = ?,
                    editable = ?, visible = ?, sort_no = ?, updated_at = ?
                WHERE tenant_id = ? AND attr_key = ?
                """,
                attrName, valueType, optionsJson, defaultValue, editable ? 1 : 0, visible ? 1 : 0, sortNo,
                LocalDateTime.now(), tenantId, attrKey);
    }

    public void updateDefStatus(String tenantId, String attrKey, String status) {
        jdbcTemplate.update("UPDATE tag_ext_attr_def SET status = ?, updated_at = ? WHERE tenant_id = ? AND attr_key = ?",
                status, LocalDateTime.now(), tenantId, attrKey);
    }

    /** 某主体下的全部扩展值 */
    public Map<String, String> values(String tenantId, String subjectType, String subjectCode) {
        Map<String, String> map = new HashMap<>();
        jdbcTemplate.query("SELECT attr_key, attr_value FROM tag_ext_attr " +
                        "WHERE tenant_id = ? AND subject_type = ? AND subject_code = ?",
                rs -> {
                    map.put(rs.getString("attr_key"), rs.getString("attr_value"));
                }, tenantId, subjectType, subjectCode);
        return map;
    }

    /** 租户下全部标签扩展值：tagCode → (attrKey → value)，用于列表批量渲染 */
    public Map<String, Map<String, String>> allTagValues(String tenantId) {
        Map<String, Map<String, String>> out = new HashMap<>();
        jdbcTemplate.query("SELECT subject_code, attr_key, attr_value FROM tag_ext_attr " +
                        "WHERE tenant_id = ? AND subject_type = 'TAG'",
                rs -> {
                    out.computeIfAbsent(rs.getString("subject_code"), k -> new HashMap<>())
                            .put(rs.getString("attr_key"), rs.getString("attr_value"));
                }, tenantId);
        return out;
    }

    public void upsertValue(String tenantId, String subjectType, String subjectCode,
                            String attrKey, String value, String updatedBy) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
                    updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_by = VALUES(updated_by),
                    updated_at = VALUES(updated_at)
                """, tenantId, subjectType, subjectCode, attrKey, value, updatedBy, now, now);
    }

    public void deleteValue(String tenantId, String subjectType, String subjectCode, String attrKey) {
        jdbcTemplate.update("DELETE FROM tag_ext_attr WHERE tenant_id = ? AND subject_type = ? " +
                "AND subject_code = ? AND attr_key = ?", tenantId, subjectType, subjectCode, attrKey);
    }

    /** 一级维度及其优先级（维度取自 tag_category，优先级取扩展值） */
    public List<TagExtModels.DimPriority> dimPriorities(String tenantId) {
        return jdbcTemplate.query("""
                SELECT c.category_code, c.category_name,
                       (SELECT a.attr_value FROM tag_ext_attr a
                         WHERE a.tenant_id = ? AND a.subject_type = 'DIM1'
                           AND a.subject_code = c.category_code AND a.attr_key = 'dim1_priority'
                         LIMIT 1) AS priority
                FROM tag_category c
                WHERE c.parent_code IS NULL
                  AND EXISTS (SELECT 1 FROM tag_meta t
                               WHERE t.dim1_code = c.category_code AND t.status <> 'archived')
                ORDER BY c.sort_no
                """, (rs, i) -> {
            String p = rs.getString("priority");
            Integer priority = null;
            if (p != null && !p.isBlank()) {
                try {
                    priority = Integer.valueOf(p.trim());
                } catch (NumberFormatException ignored) {
                    // 脏值按未配置处理
                }
            }
            return new TagExtModels.DimPriority(rs.getString("category_code"), rs.getString("category_name"), priority);
        }, tenantId);
    }
}
