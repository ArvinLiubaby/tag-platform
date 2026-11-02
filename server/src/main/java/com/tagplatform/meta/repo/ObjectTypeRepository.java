package com.tagplatform.meta.repo;

import com.tagplatform.meta.model.ObjectTypeMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * 对象类型注册表仓储（object_type_meta 表）。
 */
@Repository
public class ObjectTypeRepository {

    private static final RowMapper<ObjectTypeMeta> MAPPER = (rs, rowNum) -> new ObjectTypeMeta(
            rs.getString("object_type"),
            rs.getString("type_name"),
            rs.getString("id_field"),
            rs.getString("id_pattern"),
            rs.getString("object_class"),
            rs.getInt("default_ttl"),
            rs.getString("status"));

    private final JdbcTemplate jdbcTemplate;

    public ObjectTypeRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<ObjectTypeMeta> findByType(String objectType) {
        return jdbcTemplate.query("SELECT * FROM object_type_meta WHERE object_type = ?", MAPPER, objectType)
                .stream().findFirst();
    }

    public List<ObjectTypeMeta> listAll() {
        return jdbcTemplate.query("SELECT * FROM object_type_meta ORDER BY object_type", MAPPER);
    }
}
