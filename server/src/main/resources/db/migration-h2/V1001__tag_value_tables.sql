-- ============================================================================
-- demo/H2 专用迁移：标签值表（生产环境这两张表位于 StarRocks，不执行本脚本）
-- 本脚本位于独立 Flyway location（classpath:db/migration-h2），
-- 仅 demo 模式在 application-demo.yml 中追加该 location 后执行。
-- 版本号取 1001 高位，避免与 db/migration 中的通用迁移版本冲突。
-- ============================================================================

-- 标签最新值宽表（对应 StarRocks Primary Key 模型，H2 以联合主键模拟 upsert 收敛）
CREATE TABLE tag_value_latest (
  tenant_id     VARCHAR(64)   NOT NULL,
  object_type   VARCHAR(32)   NOT NULL,
  object_id     VARCHAR(128)  NOT NULL,
  tag_code      VARCHAR(64)   NOT NULL,
  tag_value     VARCHAR(2048),
  value_type    VARCHAR(16),
  status        VARCHAR(16)   NOT NULL,
  biz_time      DATETIME,
  operate_time  DATETIME,
  expire_time   DATETIME,
  operator_id   VARCHAR(64),
  operator_name VARCHAR(128),
  operator_type VARCHAR(16),
  operator_org  VARCHAR(128),
  source        VARCHAR(64),
  confidence    DOUBLE,
  remark        VARCHAR(2048),
  evidence_url  VARCHAR(512),
  audit_by      VARCHAR(64),
  audit_time    DATETIME,
  audit_remark  VARCHAR(1024),
  request_id    VARCHAR(64),
  op_time       DATETIME      NOT NULL,
  ver           BIGINT,
  ext_json      VARCHAR(2048),
  PRIMARY KEY (tenant_id, object_type, object_id, tag_code)
);

-- 标签变更流水（保留 90 天，审计与血缘追溯；StarRocks 侧按月分区）
CREATE TABLE tag_value_history (
  id            BIGINT        NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(64)   NOT NULL,
  object_type   VARCHAR(32)   NOT NULL,
  object_id     VARCHAR(128)  NOT NULL,
  tag_code      VARCHAR(64)   NOT NULL,
  tag_value     VARCHAR(2048),
  value_type    VARCHAR(16),
  status        VARCHAR(16)   NOT NULL,
  biz_time      DATETIME,
  operate_time  DATETIME,
  expire_time   DATETIME,
  operator_id   VARCHAR(64),
  operator_name VARCHAR(128),
  operator_type VARCHAR(16),
  operator_org  VARCHAR(128),
  source        VARCHAR(64),
  confidence    DOUBLE,
  remark        VARCHAR(2048),
  evidence_url  VARCHAR(512),
  audit_by      VARCHAR(64),
  audit_time    DATETIME,
  audit_remark  VARCHAR(1024),
  request_id    VARCHAR(64),
  op_time       DATETIME      NOT NULL,
  ver           BIGINT,
  ext_json      VARCHAR(2048),
  PRIMARY KEY (id)
);
CREATE INDEX idx_tvh_operator ON tag_value_history (tenant_id, operator_id, op_time);
CREATE INDEX idx_tvh_object ON tag_value_history (tenant_id, object_type, object_id, tag_code, op_time);
