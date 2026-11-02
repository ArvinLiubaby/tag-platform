package com.tagplatform.meta.repo;

import com.tagplatform.meta.model.TenantInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 租户仓储（tenant 表，控制台管理）。
 */
@Repository
public class TenantRepository {

    private static final RowMapper<TenantInfo> MAPPER = (rs, rowNum) -> new TenantInfo(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("tenant_name"),
            rs.getString("access_system"),
            rs.getString("status"),
            rs.getInt("write_qps"),
            rs.getInt("read_qps"),
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TenantRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<TenantInfo> listAll() {
        return jdbcTemplate.query("SELECT * FROM tenant ORDER BY created_at", MAPPER);
    }

    public Optional<TenantInfo> findByTenantId(String tenantId) {
        return jdbcTemplate.query("SELECT * FROM tenant WHERE tenant_id = ?", MAPPER, tenantId)
                .stream().findFirst();
    }

    public void insert(String tenantId, String tenantName, String accessSystem, int writeQps, int readQps) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                INSERT INTO tenant (tenant_id, tenant_name, access_system, status, write_qps, read_qps,
                                    created_at, updated_at)
                VALUES (?, ?, ?, 'enabled', ?, ?, ?, ?)
                """,
                tenantId, tenantName, accessSystem, writeQps, readQps, now, now);
    }

    public int updateStatus(String tenantId, String status) {
        return jdbcTemplate.update("UPDATE tenant SET status = ?, updated_at = ? WHERE tenant_id = ?",
                status, LocalDateTime.now(), tenantId);
    }
}
