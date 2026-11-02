package com.tagplatform.meta.composition;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** 组合标签仓储（tag_composition）。 */
@Repository
public class TagCompositionRepository {

    private static final RowMapper<TagComposition> MAPPER = (rs, rowNum) -> new TagComposition(
            rs.getLong("id"),
            rs.getString("tag_code"),
            rs.getString("tenant_id"),
            rs.getString("object_type"),
            rs.getString("logic"),
            rs.getString("conditions_json"),
            rs.getString("output_type"),
            rs.getString("output_json"),
            rs.getString("missing_policy"),
            rs.getString("description"),
            rs.getString("status"),
            rs.getInt("rule_version"),
            rs.getString("created_by"),
            rs.getString("updated_by"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TagCompositionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TagComposition> find(String tagCode) {
        return jdbcTemplate.query("SELECT * FROM tag_composition WHERE tag_code = ?", MAPPER, tagCode)
                .stream().findFirst();
    }

    public List<TagComposition> listAll(String status) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tag_composition");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" WHERE status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY updated_at DESC, id DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<TagComposition> listPublishedByObjectType(String objectType) {
        return jdbcTemplate.query("""
                SELECT * FROM tag_composition
                 WHERE status = 'published' AND (object_type = ? OR object_type IS NULL OR object_type = '')
                 ORDER BY id
                """, MAPPER, objectType);
    }

    public void insert(TagComposition c) {
        jdbcTemplate.update("""
                INSERT INTO tag_composition (tag_code, tenant_id, object_type, logic, conditions_json,
                                             output_type, output_json, missing_policy, description, status,
                                             rule_version, created_by, updated_by, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, c.tagCode(), c.tenantId(), c.objectType(), c.logic(), c.conditionsJson(),
                c.outputType(), c.outputJson(), c.missingPolicy(), c.description(), c.status(), c.ruleVersion(),
                c.createdBy(), c.updatedBy(), c.createdAt(), c.updatedAt());
    }

    public void updateRule(String tagCode, String objectType, String logic, String conditionsJson,
                           String outputType, String outputJson, String missingPolicy, String description,
                           String tenantId, String updatedBy) {
        jdbcTemplate.update("""
                UPDATE tag_composition SET object_type = ?, logic = ?, conditions_json = ?, output_type = ?,
                       output_json = ?, missing_policy = ?, description = ?, tenant_id = ?,
                       rule_version = rule_version + 1, updated_by = ?, updated_at = ?
                 WHERE tag_code = ?
                """, objectType, logic, conditionsJson, outputType, outputJson, missingPolicy, description,
                tenantId, updatedBy, LocalDateTime.now(), tagCode);
    }

    public void delete(String tagCode) {
        jdbcTemplate.update("DELETE FROM tag_composition WHERE tag_code = ?", tagCode);
    }

    /** 是否仍在标签市场上架（删除前置校验，避免市场中出现孤儿标签） */
    public boolean hasActiveMarketListing(String tagCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_market_listing WHERE tag_code = ? AND status = 'listed'",
                Integer.class, tagCode);
        return count != null && count > 0;
    }

    public void updateStatus(String tagCode, String status, String updatedBy) {
        jdbcTemplate.update("""
                UPDATE tag_composition SET status = ?, updated_by = ?, updated_at = ? WHERE tag_code = ?
                """, status, updatedBy, LocalDateTime.now(), tagCode);
    }
}
