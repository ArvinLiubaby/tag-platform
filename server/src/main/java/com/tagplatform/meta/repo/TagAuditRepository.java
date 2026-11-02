package com.tagplatform.meta.repo;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 标签定义审核流水仓储（tag_audit 表）。
 */
@Repository
public class TagAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public TagAuditRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TagAuditInfo> list(String status) {
        String sql = "SELECT * FROM tag_audit";
        if (status != null && !status.isBlank()) {
            sql += " WHERE status = ?";
            return jdbcTemplate.query(sql + " ORDER BY created_at DESC", MAPPER, status);
        }
        return jdbcTemplate.query(sql + " ORDER BY created_at DESC", MAPPER);
    }

    public void insert(String tagCode, String actionType, String submitBy, String status) {
        jdbcTemplate.update("INSERT INTO tag_audit (tag_code, action_type, submit_by, status, created_at) VALUES (?, ?, ?, ?, ?)",
                tagCode, actionType, submitBy, status, LocalDateTime.now());
    }

    public int decide(long id, String status, String auditBy, String auditRemark) {
        return jdbcTemplate.update(
                "UPDATE tag_audit SET status = ?, audit_by = ?, audit_remark = ? WHERE id = ? AND status = 'reviewing'",
                status, auditBy, auditRemark, id);
    }

    public Optional<TagAuditInfo> findById(long id) {
        return jdbcTemplate.query("SELECT * FROM tag_audit WHERE id = ?", MAPPER, id).stream().findFirst();
    }

    /** 统计某标签编码下待审核的定义变更数量 */
    public int countPendingByTag(String tagCode) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_audit WHERE tag_code = ? AND status = 'reviewing'",
                Integer.class, tagCode);
        return count == null ? 0 : count;
    }

    private static final org.springframework.jdbc.core.RowMapper<TagAuditInfo> MAPPER = (rs, rowNum) -> new TagAuditInfo(
            rs.getLong("id"),
            rs.getString("tag_code"),
            rs.getString("action_type"),
            rs.getString("submit_by"),
            rs.getString("audit_by"),
            rs.getString("audit_remark"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());

    /**
     * 标签定义审核流水视图。
     */
    public record TagAuditInfo(
            Long id,
            String tagCode,
            String actionType,
            String submitBy,
            String auditBy,
            String auditRemark,
            String status,
            LocalDateTime createdAt) {
    }
}
