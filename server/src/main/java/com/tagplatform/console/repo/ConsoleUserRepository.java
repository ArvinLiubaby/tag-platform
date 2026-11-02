package com.tagplatform.console.repo;

import com.tagplatform.console.model.ConsoleUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 控制台用户仓储（console_user 表）。
 */
@Repository
public class ConsoleUserRepository {

    private static final RowMapper<ConsoleUser> MAPPER = (rs, rowNum) -> new ConsoleUser(
            rs.getLong("id"),
            rs.getString("username"),
            rs.getString("password_hash"),
            rs.getString("display_name"),
            rs.getString("user_role"),
            rs.getString("status"),
            rs.getString("tenant_id"),
            rs.getString("operator_id"),
            rs.getString("operator_name"),
            rs.getString("operator_org"),
            rs.getString("team"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public ConsoleUserRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<ConsoleUser> findAll() {
        return jdbcTemplate.query("SELECT * FROM console_user ORDER BY id", MAPPER);
    }

    public Optional<ConsoleUser> findByUsername(String username) {
        return jdbcTemplate.query("SELECT * FROM console_user WHERE username = ?", MAPPER, username)
                .stream().findFirst();
    }

    public Optional<ConsoleUser> findById(Long id) {
        if (id == null) return Optional.empty();
        return jdbcTemplate.query("SELECT * FROM console_user WHERE id = ?", MAPPER, id)
                .stream().findFirst();
    }

    public void insert(String username, String passwordHash, String displayName, String userRole,
                       String tenantId, String operatorId, String operatorName, String operatorOrg,
                       String team) {
        jdbcTemplate.update("""
                INSERT INTO console_user (username, password_hash, display_name, user_role, status,
                                          tenant_id, operator_id, operator_name, operator_org, team, created_at)
                VALUES (?, ?, ?, ?, 'enabled', ?, ?, ?, ?, ?, ?)
                """,
                username, passwordHash, displayName, userRole, tenantId, operatorId, operatorName,
                operatorOrg, team, LocalDateTime.now());
    }

    /** 任务派发候选：本租户启用中的打标员/任务管理员 */
    public List<ConsoleUser> findByTenantId(String tenantId) {
        return jdbcTemplate.query(
                "SELECT * FROM console_user WHERE tenant_id = ? ORDER BY id", MAPPER, tenantId);
    }

    public long countByTenantAndRole(String tenantId, String userRole) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM console_user WHERE tenant_id = ? AND user_role = ?",
                Long.class, tenantId, userRole);
        return count == null ? 0L : count;
    }

    public List<ConsoleUser> listAssignable(String tenantId) {
        return jdbcTemplate.query("""
                SELECT * FROM console_user
                 WHERE status = 'enabled' AND tenant_id = ?
                   AND user_role IN ('TAGGER', 'TASK_MANAGER')
                 ORDER BY display_name
                """, MAPPER, tenantId);
    }

    public int updatePasswordHash(String username, String passwordHash) {
        return jdbcTemplate.update("UPDATE console_user SET password_hash = ? WHERE username = ?",
                passwordHash, username);
    }

    public int updateStatus(String username, String status) {
        return jdbcTemplate.update("UPDATE console_user SET status = ? WHERE username = ?", status, username);
    }

    public int updateRole(String username, String userRole) {
        return jdbcTemplate.update("UPDATE console_user SET user_role = ? WHERE username = ?", userRole, username);
    }

    /** 启用中的管理员数量（用于保护最后一个管理员） */
    public long countEnabledAdmins() {
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM console_user WHERE user_role = 'ADMIN' AND status = 'enabled'", Long.class);
        return count == null ? 0L : count;
    }
}
