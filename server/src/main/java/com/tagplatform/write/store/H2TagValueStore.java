package com.tagplatform.write.store;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * H2 实现（demo，MODE=MySQL）：ON DUPLICATE KEY UPDATE 模拟 PK upsert。
 */
public class H2TagValueStore extends AbstractJdbcTagValueStore {

    private static final String UPSERT_SQL = "INSERT INTO tag_value_latest (" + COLUMNS + ") VALUES ("
            + String.join(",", java.util.Collections.nCopies(25, "?")) + ") ON DUPLICATE KEY UPDATE "
            + "tag_value = VALUES(tag_value), value_type = VALUES(value_type), status = VALUES(status), "
            + "biz_time = VALUES(biz_time), operate_time = VALUES(operate_time), expire_time = VALUES(expire_time), "
            + "operator_id = VALUES(operator_id), operator_name = VALUES(operator_name), "
            + "operator_type = VALUES(operator_type), operator_org = VALUES(operator_org), source = VALUES(source), "
            + "confidence = VALUES(confidence), remark = VALUES(remark), evidence_url = VALUES(evidence_url), "
            + "audit_by = VALUES(audit_by), audit_time = VALUES(audit_time), audit_remark = VALUES(audit_remark), "
            + "request_id = VALUES(request_id), op_time = VALUES(op_time), ver = VALUES(ver), ext_json = VALUES(ext_json)";

    public H2TagValueStore(JdbcTemplate jdbcTemplate) {
        super(jdbcTemplate);
    }

    @Override
    protected String upsertSql() {
        return UPSERT_SQL;
    }
}
