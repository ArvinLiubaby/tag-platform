package com.tagplatform.meta.repo;

import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.model.TagVersion;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 标签定义版本快照仓储（tag_meta_version）。
 */
@Repository
public class TagVersionRepository {

    private static final RowMapper<TagVersion> MAPPER = (rs, rowNum) -> new TagVersion(
            rs.getLong("id"),
            rs.getString("tag_code"),
            rs.getInt("version"),
            rs.getString("change_type"),
            rs.getString("change_by"),
            rs.getString("change_summary"),
            new TagVersion.Definition(
                    rs.getString("tag_name"),
                    rs.getString("category_code"),
                    rs.getString("value_type"),
                    rs.getString("value_range"),
                    rs.getInt("value_multiple") == 1,
                    rs.getString("tag_type"),
                    rs.getInt("need_value_audit") == 1,
                    rs.getInt("value_ttl_days"),
                    rs.getString("tenant_id"),
                    rs.getString("sensitivity"),
                    rs.getString("object_type"),
                    rs.getString("source_mode"),
                    rs.getInt("writable") == 1),
            rs.getString("composition_json"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TagVersionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(TagMeta meta, String changeType, String changeBy, String changeSummary) {
        jdbcTemplate.update("""
                INSERT INTO tag_meta_version (tag_code, version, change_type, change_by, change_summary,
                                              tag_name, category_code, value_type, value_range, value_multiple,
                                              tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity,
                                              object_type, source_mode, writable, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                meta.tagCode(), meta.version(), changeType, changeBy, changeSummary,
                meta.tagName(), meta.categoryCode(), meta.valueType(), meta.valueRange(),
                meta.valueMultiple() ? 1 : 0, meta.tagType(), meta.needValueAudit() ? 1 : 0,
                meta.valueTtlDays(), meta.tenantId(), meta.sensitivity(), meta.objectType(),
                meta.sourceMode(), meta.writable() ? 1 : 0, LocalDateTime.now());
    }

    /** 组合标签的规则随定义版本一同快照；普通标签为 null */
    public void updateCompositionJson(String tagCode, int version, String compositionJson) {
        jdbcTemplate.update("UPDATE tag_meta_version SET composition_json = ? WHERE tag_code = ? AND version = ?",
                compositionJson, tagCode, version);
    }

    public List<TagVersion> list(String tagCode, int limit) {
        return jdbcTemplate.query("""
                SELECT * FROM tag_meta_version WHERE tag_code = ?
                 ORDER BY version DESC LIMIT ?
                """, MAPPER, tagCode, Math.max(1, Math.min(limit, 200)));
    }

    public Optional<TagVersion> find(String tagCode, int version) {
        return jdbcTemplate.query("SELECT * FROM tag_meta_version WHERE tag_code = ? AND version = ?",
                MAPPER, tagCode, version).stream().findFirst();
    }

    public Optional<TagVersion> findPrevious(String tagCode, int version) {
        return jdbcTemplate.query("""
                SELECT * FROM tag_meta_version WHERE tag_code = ? AND version < ?
                 ORDER BY version DESC LIMIT 1
                """, MAPPER, tagCode, version).stream().findFirst();
    }
}
