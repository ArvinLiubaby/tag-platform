package com.tagplatform.write.store;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StarRocks 手动同步：折叠与写入都在库内完成，只发一条 INSERT ... SELECT。
 *
 * <p>用轻量 JdbcTemplate 替身记录 SQL，避免依赖真实 StarRocks；
 * 锁定「不再逐行 upsert」这个性能约束（旧实现 103 行要 11–15 秒）。</p>
 */
class StarRocksLatestSyncTest {

    private static class CapturingJdbc extends JdbcTemplate {
        private final long foldedRows;
        private final List<String> counts = new ArrayList<>();
        private final List<String> updates = new ArrayList<>();

        CapturingJdbc(long foldedRows) {
            this.foldedRows = foldedRows;
        }

        @Override
        public <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            counts.add(sql);
            return requiredType.cast(Long.valueOf(foldedRows));
        }

        @Override
        public int update(String sql, Object... args) {
            updates.add(sql);
            return (int) foldedRows;
        }
    }

    @Test
    void 整租户同步只发一条InsertSelect() {
        CapturingJdbc jdbc = new CapturingJdbc(103);
        int rows = new StarRocksTagValueStore(jdbc).syncLatestFromHistory("risk_control", null, null);

        assertThat(rows).isEqualTo(103);
        assertThat(jdbc.counts).hasSize(1);
        assertThat(jdbc.counts.get(0)).contains("COUNT(*)").contains("ROW_NUMBER()");
        assertThat(jdbc.updates).hasSize(1);
        assertThat(jdbc.updates.get(0))
                .startsWith("INSERT INTO tag_value_latest")
                .contains("SELECT")
                .contains("ROW_NUMBER()")
                .contains("LIMIT 20000");
    }

    @Test
    void 指定对象时SQL带上对象过滤() {
        CapturingJdbc jdbc = new CapturingJdbc(1);
        new StarRocksTagValueStore(jdbc).syncLatestFromHistory("risk_control", "driver", "DRV91001");

        assertThat(jdbc.updates.get(0))
                .contains("AND object_type = ?")
                .contains("AND object_id = ?");
    }

    @Test
    void 无折叠行时不发起写入() {
        CapturingJdbc jdbc = new CapturingJdbc(0);
        assertThat(new StarRocksTagValueStore(jdbc)
                .syncLatestFromHistory("risk_control", "driver", "NOPE")).isZero();
        assertThat(jdbc.updates).isEmpty();
    }
}
