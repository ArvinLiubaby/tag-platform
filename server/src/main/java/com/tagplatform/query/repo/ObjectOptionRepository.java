package com.tagplatform.query.repo;

import com.tagplatform.write.store.TagValueStore;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 画像页「对象 ID」下拉选项。
 *
 * <p><b>事实源是 tag_value_latest</b>：外部程序直写 latest 的新对象无需登记索引即可出现。
 * object_index 退居辅助角色——补对象名称、补「任务已导入但尚无标签值」的对象。
 * 之所以不能只读 object_index，是因为它仅在平台写入链路（开放 API / 工作台 / 任务）里登记，
 * 外部直写 latest 的对象永远不会进索引，会出现「画像查得到、下拉却为空」。</p>
 *
 * <p>仅作输入辅助——对象可能尚未有值，因此前端保留自由输入。</p>
 */
@Repository
public class ObjectOptionRepository {

    private final JdbcTemplate jdbcTemplate;
    private final TagValueStore tagValueStore;

    public ObjectOptionRepository(JdbcTemplate jdbcTemplate, TagValueStore tagValueStore) {
        this.jdbcTemplate = jdbcTemplate;
        this.tagValueStore = tagValueStore;
    }

    public record Option(String objectId, String objectName, LocalDateTime lastSeen) {
    }

    public List<Option> search(String tenantId, String objectType, String keyword, int limit) {
        int size = Math.max(1, Math.min(limit, 500));
        List<Option> indexed = searchIndex(tenantId, objectType, keyword, size);

        List<TagValueStore.ObjectRef> live;
        try {
            live = tagValueStore.listObjects(tenantId, objectType, keyword, size);
        } catch (RuntimeException e) {
            // StarRocks 不可用时退回索引：下拉只是输入辅助，不能影响手输查询
            return indexed;
        }
        if (live.isEmpty()) {
            return indexed;
        }

        // latest 里通常没写 object_name（外部直写尤为常见），用索引里的名称补
        List<String> needName = new ArrayList<>();
        for (TagValueStore.ObjectRef ref : live) {
            if (ref.objectName() == null || ref.objectName().isBlank()) {
                needName.add(ref.objectId());
            }
        }
        Map<String, String> names = needName.isEmpty() ? Map.of() : findObjectNames(tenantId, needName);

        // 以 object_id 合并：latest 的结果在前（按最近值时间），索引独有的对象补在后面
        LinkedHashMap<String, Option> merged = new LinkedHashMap<>();
        for (TagValueStore.ObjectRef ref : live) {
            String name = ref.objectName() != null && !ref.objectName().isBlank()
                    ? ref.objectName() : names.get(ref.objectId());
            merged.putIfAbsent(ref.objectId(), new Option(ref.objectId(), name, ref.lastSeen()));
        }
        for (Option option : indexed) {
            merged.putIfAbsent(option.objectId(), option);
        }

        List<Option> result = new ArrayList<>(merged.values());
        result.sort(Comparator.comparing(Option::lastSeen,
                Comparator.nullsLast(Comparator.reverseOrder())));
        return result.size() > size ? result.subList(0, size) : result;
    }

    private List<Option> searchIndex(String tenantId, String objectType, String keyword, int limit) {
        StringBuilder sql = new StringBuilder("SELECT object_id, object_name, last_seen FROM object_index WHERE tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (objectType != null && !objectType.isBlank()) {
            sql.append(" AND object_type = ?");
            args.add(objectType);
        }
        if (keyword != null && !keyword.isBlank()) {
            sql.append(" AND object_id LIKE ?");
            args.add("%" + keyword + "%");
        }
        sql.append(" ORDER BY last_seen DESC LIMIT ?");
        args.add(limit);
        return jdbcTemplate.query(sql.toString(),
                (rs, rowNum) -> new Option(rs.getString("object_id"), rs.getString("object_name"),
                        rs.getTimestamp("last_seen").toLocalDateTime()),
                args.toArray());
    }

    /** 批量兜底：一次 IN 查询取回多个对象的名称（object_id → object_name，名称非空才返回） */
    public Map<String, String> findObjectNames(String tenantId, List<String> objectIds) {
        Map<String, String> result = new HashMap<>();
        if (objectIds == null || objectIds.isEmpty()) {
            return result;
        }
        List<String> distinct = objectIds.stream().distinct().toList();
        // 分片查询：IN 参数过多会超出驱动/数据库的占位符上限
        final int batch = 500;
        for (int from = 0; from < distinct.size(); from += batch) {
            List<String> chunk = distinct.subList(from, Math.min(from + batch, distinct.size()));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            List<Object> args = new ArrayList<>();
            args.add(tenantId);
            args.addAll(chunk);
            jdbcTemplate.query("SELECT object_id, object_name FROM object_index WHERE tenant_id = ?" +
                    " AND object_name IS NOT NULL AND object_id IN (" + placeholders + ")",
                    rs -> {
                        result.putIfAbsent(rs.getString("object_id"), rs.getString("object_name"));
                    }, args.toArray());
        }
        return result;
    }

    /** 画像查询展示用：对象名称（可能为空） */
    public String findObjectName(String tenantId, String objectType, String objectId) {
        List<String> names = jdbcTemplate.queryForList("""
                SELECT object_name FROM object_index
                 WHERE tenant_id = ? AND object_type = ? AND object_id = ? AND object_name IS NOT NULL
                """, String.class, tenantId, objectType, objectId);
        return names.isEmpty() ? null : names.get(0);
    }
}
