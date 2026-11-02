-- ============================================================================
-- V1 标签画像采集平台 · 元数据与治理库（MySQL 8 / OceanBase MySQL 模式 / demo H2 兼容）
-- 兼容约定：不使用 ENGINE / COMMENT / JSON 类型 / ON UPDATE 等引擎特性，
--          时间戳统一由应用层写入，保证同一份脚本可跑 MySQL 与 H2(MySQL 模式)。
-- ============================================================================

-- 租户（接入的业务系统/平台来源）
CREATE TABLE tenant (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(64)  NOT NULL,
  tenant_name   VARCHAR(128) NOT NULL,
  access_system VARCHAR(128),
  status        VARCHAR(16)  NOT NULL,
  write_qps     INT          NOT NULL,
  read_qps      INT          NOT NULL,
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id)
);

-- 应用凭证（AppKey / Secret。Secret 采用可逆加密存储：enc:v1:<base64>；demo 种子为明文占位）
CREATE TABLE app_credential (
  id         BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id  VARCHAR(64)  NOT NULL,
  app_key    VARCHAR(64)  NOT NULL,
  secret_enc VARCHAR(512) NOT NULL,
  status     VARCHAR(16)  NOT NULL,
  created_at DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (app_key)
);

-- 标签类目（多级）
CREATE TABLE tag_category (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  category_code VARCHAR(64)  NOT NULL,
  category_name VARCHAR(128) NOT NULL,
  parent_code   VARCHAR(64),
  sort_no       INT          NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (category_code)
);

-- 标签字典（元数据）
CREATE TABLE tag_meta (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  tag_code         VARCHAR(64)  NOT NULL,
  tag_name         VARCHAR(128) NOT NULL,
  category_code    VARCHAR(64),
  value_type       VARCHAR(16)  NOT NULL,
  value_range      TEXT,
  tag_type         VARCHAR(16)  NOT NULL,
  need_value_audit TINYINT      NOT NULL,
  value_ttl_days   INT          NOT NULL,
  tenant_id        VARCHAR(64)  NOT NULL,
  sensitivity      VARCHAR(8)   NOT NULL,
  status           VARCHAR(16)  NOT NULL,
  version          INT          NOT NULL,
  created_at       DATETIME     NOT NULL,
  updated_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tag_code)
);

-- 标签定义审核流水（注册/变更审批留痕）
CREATE TABLE tag_audit (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tag_code     VARCHAR(64)  NOT NULL,
  action_type  VARCHAR(32)  NOT NULL,
  submit_by    VARCHAR(64)  NOT NULL,
  audit_by     VARCHAR(64),
  audit_remark VARCHAR(512),
  status       VARCHAR(16)  NOT NULL,
  created_at   DATETIME     NOT NULL,
  PRIMARY KEY (id)
);

-- 对象类型注册表（driver / vehicle / fleet / alarm_event，可扩展）
CREATE TABLE object_type_meta (
  object_type  VARCHAR(32)  NOT NULL,
  type_name    VARCHAR(64)  NOT NULL,
  id_field     VARCHAR(64)  NOT NULL,
  id_pattern   VARCHAR(256),
  object_class VARCHAR(16)  NOT NULL,
  default_ttl  INT          NOT NULL,
  status       VARCHAR(16)  NOT NULL,
  PRIMARY KEY (object_type)
);

-- 对象索引（写入自动登记，首次 / 最近出现时间）
CREATE TABLE object_index (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(64)  NOT NULL,
  object_type VARCHAR(32)  NOT NULL,
  object_id   VARCHAR(128) NOT NULL,
  first_seen  DATETIME     NOT NULL,
  last_seen   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, object_type, object_id)
);

-- 幂等登记表（biz_request_id 24 小时去重，重复提交返回首次结果）
CREATE TABLE biz_request_id (
  id             BIGINT      NOT NULL AUTO_INCREMENT,
  biz_request_id VARCHAR(96) NOT NULL,
  tenant_id      VARCHAR(64) NOT NULL,
  status         VARCHAR(16) NOT NULL,
  response_json  TEXT,
  created_at     DATETIME    NOT NULL,
  updated_at     DATETIME    NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (biz_request_id)
);

-- Outbox 事件表（与业务同事务落库；V2 切换 Kafka 投递）
CREATE TABLE outbox_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  event_id    VARCHAR(64)  NOT NULL,
  event_type  VARCHAR(64)  NOT NULL,
  tenant_id   VARCHAR(64)  NOT NULL,
  payload     TEXT         NOT NULL,
  status      VARCHAR(16)  NOT NULL,
  retry_count INT          NOT NULL,
  created_at  DATETIME     NOT NULL,
  delivered_at DATETIME,
  PRIMARY KEY (id),
  UNIQUE (event_id)
);

-- 审计日志（全量操作留痕，保留 3 年）
CREATE TABLE audit_log (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  request_id  VARCHAR(64),
  actor       VARCHAR(128) NOT NULL,
  actor_role  VARCHAR(32),
  action_type VARCHAR(64)  NOT NULL,
  tenant_id   VARCHAR(64),
  object_type VARCHAR(32),
  object_id   VARCHAR(128),
  tag_code    VARCHAR(64),
  detail      TEXT,
  result      VARCHAR(32),
  source_ip   VARCHAR(64),
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX idx_audit_log_created ON audit_log (created_at);
CREATE INDEX idx_audit_log_action ON audit_log (action_type, tenant_id);

-- 配额登记（MVP 占位，演示读表；正式限流走 Redis 计数器）
CREATE TABLE quota (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id   VARCHAR(64)  NOT NULL,
  quota_dim   VARCHAR(32)  NOT NULL,
  limit_value BIGINT       NOT NULL,
  used_value  BIGINT       NOT NULL,
  updated_at  DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, quota_dim)
);

-- 控制台用户（轻量 RBAC：ADMIN / AUDITOR / VIEWER）
CREATE TABLE console_user (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  username      VARCHAR(64)  NOT NULL,
  password_hash VARCHAR(256) NOT NULL,
  display_name  VARCHAR(128) NOT NULL,
  user_role     VARCHAR(16)  NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  created_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (username)
);
