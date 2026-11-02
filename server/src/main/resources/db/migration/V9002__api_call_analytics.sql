-- ============================================================================
-- V9002 开放 API 调用埋点与标签访问日统计
-- 兼容：MySQL 8 / OceanBase MySQL 模式 / H2(MySQL 模式)
-- 说明：
--   1. 本脚本不使用 ENGINE / COMMENT / JSON / ON UPDATE 等引擎特性。
--   2. MySQL / OceanBase 的表与字段注释见 db/migration-mysql/V9003。
--   3. 版本号取 9002：元数据库已应用 V9001（注释迁移），避免 Flyway 乱序。
-- ============================================================================

-- 开放 API 调用流水：每次外部调用一行（鉴权失败也会记录，tenant_id 可能为空）
CREATE TABLE api_call_log (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(64),
  app_key      VARCHAR(64),
  method       VARCHAR(8)   NOT NULL,
  endpoint     VARCHAR(160) NOT NULL,
  http_status  INT          NOT NULL,
  biz_code     INT,
  success      TINYINT      NOT NULL,
  duration_ms  INT          NOT NULL,
  write_count  INT          NOT NULL,
  query_count  INT          NOT NULL,
  delete_count INT          NOT NULL,
  operator_id  VARCHAR(64),
  object_type  VARCHAR(32),
  object_id    VARCHAR(128),
  request_id   VARCHAR(64),
  client_ip    VARCHAR(64),
  called_at    DATETIME     NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX idx_api_call_time ON api_call_log (called_at);
CREATE INDEX idx_api_call_tenant_time ON api_call_log (tenant_id, called_at);
CREATE INDEX idx_api_call_app_time ON api_call_log (app_key, called_at);
CREATE INDEX idx_api_call_endpoint_time ON api_call_log (endpoint, called_at);

-- 标签访问日统计：租户 + 标签 + 动作（query/write/delete）按天聚合
CREATE TABLE tag_access_daily (
  stat_date      DATE         NOT NULL,
  tenant_id      VARCHAR(64)  NOT NULL,
  tag_code       VARCHAR(64)  NOT NULL,
  action         VARCHAR(16)  NOT NULL,
  access_count   BIGINT       NOT NULL,
  last_access_at DATETIME     NOT NULL,
  PRIMARY KEY (stat_date, tenant_id, tag_code, action)
);
CREATE INDEX idx_tag_access_date_action ON tag_access_daily (stat_date, action);
CREATE INDEX idx_tag_access_date_action_tag ON tag_access_daily (stat_date, action, tag_code);
