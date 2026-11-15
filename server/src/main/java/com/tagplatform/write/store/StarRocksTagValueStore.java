package com.tagplatform.write.store;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * StarRocks 实现（生产）：Primary Key 模型，INSERT 即 upsert（last-write-wins）。
 */
public class StarRocksTagValueStore extends AbstractJdbcTagValueStore {

    private static final String UPSERT_SQL = "INSERT INTO tag_value_latest (" + COLUMNS + ") VALUES ("
            + String.join(",", java.util.Collections.nCopies(26, "?")) + ")";

    public StarRocksTagValueStore(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    protected String upsertSql() {
        return UPSERT_SQL;
    }

    /**
     * 手动同步走单条 INSERT ... SELECT：折叠与写入都在 StarRocks 内完成。
     *
     * <p>旧实现把折叠出的 N 行取回 JVM 再逐行 upsert，每行一次网络往返（103 行约 11–15 秒）。
     * 等待窗口过长时，用户刷新/切页会让浏览器取消 fetch，前端只看到「Failed to fetch」，
     * 服务端却查不到任何异常。改成库内一把写入后耗时降到 1 秒级。</p>
     *
     * <p>行数用折叠子查询 COUNT 取得，不依赖 INSERT ... SELECT 的 affected rows
     *（StarRocks 对导入类语句的返回值不稳定）。</p>
     */
    @Override
    public int syncLatestFromHistory(String tenantId, String objectType, String objectId) {
        FoldedQuery folded = foldedQuery(tenantId, objectType, objectId);
        Long count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM (" + folded.sql() + ") t WHERE rn = 1", Long.class, folded.args());
        int rows = count == null ? 0 : (int) Math.min(count, SYNC_MAX_ROWS);
        if (rows > 0) {
            String insert = "INSERT INTO tag_value_latest (" + COLUMNS + ") SELECT " + COLUMNS
                    + " FROM (" + folded.sql() + ") t WHERE rn = 1 LIMIT " + SYNC_MAX_ROWS;
            jdbcTemplate.update(insert, folded.args());
        }
        return rows;
    }
}
