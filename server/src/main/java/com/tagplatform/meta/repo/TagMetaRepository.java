package com.tagplatform.meta.repo;

import com.tagplatform.meta.model.TagMeta;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * 标签字典仓储（tag_meta / tag_category 表）。
 */
@Repository
public class TagMetaRepository {

    private static final RowMapper<TagMeta> MAPPER = (rs, rowNum) -> new TagMeta(
            rs.getLong("id"),
            rs.getString("tag_code"),
            rs.getString("tag_name"),
            rs.getString("category_code"),
            rs.getString("dim1_code"),
            rs.getString("dim2_code"),
            (Integer) rs.getObject("priority"),
            rs.getString("value_type"),
            rs.getString("unit"),
            rs.getString("value_range"),
            rs.getInt("value_multiple") == 1,
            rs.getString("tag_type"),
            rs.getInt("need_value_audit") == 1,
            rs.getInt("value_ttl_days"),
            rs.getString("tenant_id"),
            rs.getString("sensitivity"),
            rs.getString("status"),
            rs.getInt("version"),
            rs.getString("object_type"),
            rs.getString("source_mode"),
            rs.getInt("writable") == 1,
            rs.getTimestamp("created_at").toLocalDateTime(),
            rs.getTimestamp("updated_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;

    public TagMetaRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<TagMeta> findByCode(String tagCode) {
        return jdbcTemplate.query("SELECT * FROM tag_meta WHERE tag_code = ?", MAPPER, tagCode)
                .stream().findFirst();
    }

    public List<TagMeta> findByCodes(Collection<String> tagCodes) {
        if (tagCodes == null || tagCodes.isEmpty()) {
            return List.of();
        }
        String placeholders = String.join(",", Collections.nCopies(tagCodes.size(), "?"));
        return jdbcTemplate.query("SELECT * FROM tag_meta WHERE tag_code IN (" + placeholders + ")",
                MAPPER, tagCodes.toArray());
    }

    /** 开放 API 可见集合：online 且（PUBLIC 或适用租户列表包含请求租户） */
    public List<TagMeta> listVisibleOnline(String tenantId) {
        return jdbcTemplate.query("""
                SELECT * FROM tag_meta
                 WHERE status = 'online'
                   AND (LOCATE(',PUBLIC,', CONCAT(',', REPLACE(tenant_id, ' ', ''), ',')) > 0
                        OR LOCATE(CONCAT(',', ?, ','), CONCAT(',', REPLACE(tenant_id, ' ', ''), ',')) > 0)
                 ORDER BY tag_code
                """, MAPPER, tenantId);
    }

    /** 控制台列表：可选状态、租户、敏感级、对象与关键字过滤；默认排除已归档；已彻底删除（deleted）无条件排除 */
    public List<TagMeta> list(String status, String keyword, String tenantId, String sensitivity,
                              String objectType, boolean includeArchived) {
        StringBuilder sql = new StringBuilder("SELECT * FROM tag_meta WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        if (status != null && !status.isBlank()) {
            sql.append(" AND status = ? AND status <> 'deleted'");
            args.add(status);
        } else if (includeArchived) {
            sql.append(" AND status <> 'deleted'");
        } else {
            sql.append(" AND status NOT IN ('archived', 'deleted')");
        }
        if (tenantId != null && !tenantId.isBlank()) {
            // 适用租户为逗号分隔多值；PUBLIC 对所有租户可见
            sql.append(" AND (LOCATE(CONCAT(',', ?, ','), CONCAT(',', REPLACE(tenant_id, ' ', ''), ',')) > 0");
            sql.append(" OR LOCATE(',PUBLIC,', CONCAT(',', REPLACE(tenant_id, ' ', ''), ',')) > 0)");
            args.add(tenantId);
        }
        if (sensitivity != null && !sensitivity.isBlank()) {
            sql.append(" AND sensitivity = ?");
            args.add(sensitivity);
        }
        if (objectType != null && !objectType.isBlank()) {
            // object_type 为逗号分隔多值；按完整 token 匹配，兼容历史数据中的空格
            sql.append(" AND LOCATE(CONCAT(',', ?, ','), CONCAT(',', REPLACE(object_type, ' ', ''), ',')) > 0");
            args.add(objectType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND (tag_code LIKE ? OR tag_name LIKE ?)");
            args.add("%" + keyword + "%");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY updated_at DESC");
        return jdbcTemplate.query(sql.toString(), MAPPER, args.toArray());
    }

    public List<CategoryInfo> listCategories() {
        return jdbcTemplate.query("SELECT category_code, category_name, parent_code, sort_no FROM tag_category ORDER BY sort_no",
                (rs, rowNum) -> new CategoryInfo(
                        rs.getString("category_code"),
                        rs.getString("category_name"),
                        rs.getString("parent_code"),
                        rs.getInt("sort_no")));
    }

    public Optional<CategoryInfo> findCategory(String categoryCode) {
        List<CategoryInfo> rows = jdbcTemplate.query(
                "SELECT category_code, category_name, parent_code, sort_no FROM tag_category WHERE category_code = ?",
                (rs, rowNum) -> new CategoryInfo(rs.getString("category_code"), rs.getString("category_name"),
                        rs.getString("parent_code"), rs.getInt("sort_no")),
                categoryCode);
        return rows.stream().findFirst();
    }

    /** 同级（同一父维度下）最大排序号，用于新增维度时给默认 sort_no */
    public int maxSortNoAmongSiblings(String parentCode) {
        String sql = parentCode == null
                ? "SELECT COALESCE(MAX(sort_no), 0) FROM tag_category WHERE parent_code IS NULL"
                : "SELECT COALESCE(MAX(sort_no), 0) FROM tag_category WHERE parent_code = ?";
        List<Integer> vals = parentCode == null
                ? jdbcTemplate.queryForList(sql, Integer.class)
                : jdbcTemplate.queryForList(sql, Integer.class, parentCode);
        return vals.isEmpty() ? 0 : vals.get(0);
    }

    public void insertCategory(String code, String name, String parentCode, int sortNo) {
        jdbcTemplate.update("INSERT INTO tag_category (category_code, category_name, parent_code, sort_no) VALUES (?, ?, ?, ?)",
                code, name, parentCode, sortNo);
    }

    public void updateCategoryName(String code, String name) {
        jdbcTemplate.update("UPDATE tag_category SET category_name = ? WHERE category_code = ?", name, code);
    }

    /** 调整父级；parentCode 传 null = 升级为一级维度（调用方已做有子节点校验） */
    public void updateCategoryParent(String code, String parentCode) {
        jdbcTemplate.update("UPDATE tag_category SET parent_code = ? WHERE category_code = ?", parentCode, code);
    }

    public void updateCategorySortNo(String code, int sortNo) {
        jdbcTemplate.update("UPDATE tag_category SET sort_no = ? WHERE category_code = ?", sortNo, code);
    }

    public void deleteCategory(String code) {
        jdbcTemplate.update("DELETE FROM tag_category WHERE category_code = ?", code);
    }

    /** 下级维度数量：>0 时不可删除或改父级 */
    public long countSubCategories(String code) {
        Long n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_category WHERE parent_code = ?", Long.class, code);
        return n == null ? 0L : n;
    }

    /** 标签引用数量（category_code / dim1_code / dim2_code 任一命中）：>0 时不可删除 */
    /** 维度被「存活标签」引用计数：归档（archived）/ 已彻底删除（deleted）的标签不占用维度 */
    public long countTagRefs(String code) {
        Long n = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_meta
                 WHERE (category_code = ? OR dim1_code = ? OR dim2_code = ?)
                   AND status NOT IN ('archived', 'deleted')
                """, Long.class, code, code, code);
        return n == null ? 0L : n;
    }

    public void insert(TagMeta meta) {
        jdbcTemplate.update("""
                INSERT INTO tag_meta (tag_code, tag_name, category_code, dim1_code, dim2_code, priority,
                                      value_type, unit, value_range, value_multiple,
                                      tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status, version,
                                      object_type, source_mode, writable, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                meta.tagCode(), meta.tagName(), meta.categoryCode(), meta.dim1Code(), meta.dim2Code(),
                meta.priority(), meta.valueType(), meta.unit(), meta.valueRange(),
                meta.valueMultiple() ? 1 : 0, meta.tagType(), meta.needValueAudit() ? 1 : 0, meta.valueTtlDays(),
                meta.tenantId(), meta.sensitivity(), meta.status(), meta.version(), meta.objectType(),
                meta.sourceMode(), meta.writable() ? 1 : 0, meta.createdAt(), meta.updatedAt());
    }

    public void updateStatus(String tagCode, String status) {
        jdbcTemplate.update("UPDATE tag_meta SET status = ?, updated_at = ? WHERE tag_code = ?",
                status, LocalDateTime.now(), tagCode);
    }

    /** 仅当当前状态匹配时更新，返回受影响行数（防止并发状态漂移） */
    public int updateStatusIfCurrent(String tagCode, String currentStatus, String newStatus) {
        return jdbcTemplate.update(
                "UPDATE tag_meta SET status = ?, updated_at = ? WHERE tag_code = ? AND status = ?",
                newStatus, LocalDateTime.now(), tagCode, currentStatus);
    }

    /**
     * 修改标签定义（tag_code / tenant_id 不可变）；按 version 做乐观锁，成功后 version + 1。
     * 返回 0 表示标签已被其他请求修改或已归档。
     */
    public int updateDefinition(String tagCode, String tagName, String categoryCode,
                                String dim1Code, String dim2Code, Integer priority, String tenantId,
                                String valueType, String unit, String valueRange, boolean valueMultiple, String tagType,
                                boolean needValueAudit, int valueTtlDays, String sensitivity, String objectType,
                                String sourceMode, boolean writable, int expectedVersion) {
        return jdbcTemplate.update("""
                UPDATE tag_meta SET tag_name = ?, category_code = ?, dim1_code = ?, dim2_code = ?, priority = ?,
                    tenant_id = ?, value_type = ?, unit = ?,
                    value_range = ?, value_multiple = ?, tag_type = ?, need_value_audit = ?, value_ttl_days = ?,
                    sensitivity = ?, object_type = ?, source_mode = ?, writable = ?, version = version + 1,
                    updated_at = ?
                WHERE tag_code = ? AND version = ? AND status <> 'archived'
                """,
                tagName, categoryCode, dim1Code, dim2Code, priority, tenantId, valueType, unit, valueRange,
                valueMultiple ? 1 : 0, tagType,
                needValueAudit ? 1 : 0, valueTtlDays, sensitivity, objectType, sourceMode, writable ? 1 : 0,
                LocalDateTime.now(), tagCode, expectedVersion);
    }

    /** 写入开关切换（控制台运维操作）；version + 1 使并发的定义编辑失效 */
    public void updateWritable(String tagCode, boolean writable) {
        jdbcTemplate.update("UPDATE tag_meta SET writable = ?, version = version + 1, updated_at = ? WHERE tag_code = ?",
                writable ? 1 : 0, LocalDateTime.now(), tagCode);
    }

    /** 标签类目视图 */
    public record CategoryInfo(String categoryCode, String categoryName, String parentCode, int sortNo) {
    }
}
