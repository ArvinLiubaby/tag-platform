package com.tagplatform.write.store;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * StarRocks 实现（生产）：Primary Key 模型，INSERT 即 upsert（last-write-wins）。
 */
public class StarRocksTagValueStore extends AbstractJdbcTagValueStore {

    private static final String UPSERT_SQL = "INSERT INTO tag_value_latest (" + COLUMNS + ") VALUES ("
            + String.join(",", java.util.Collections.nCopies(25, "?")) + ")";

    public StarRocksTagValueStore(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    protected String upsertSql() {
        return UPSERT_SQL;
    }
}
