-- ============================================================================
-- V9003 开放 API 埋点与标签访问统计：表级 / 字段级注释（MySQL 8 / OceanBase）
-- 说明：V9002 为通用 DDL（H2 demo 共用）；本脚本仅在 MySQL / OceanBase 执行。
-- ============================================================================

ALTER TABLE api_call_log
  COMMENT = '开放 API 调用流水（每次外部调用一行，含鉴权失败）',
  MODIFY COLUMN id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tenant_id    VARCHAR(64)  NULL COMMENT '租户标识（鉴权失败时可能为空）',
  MODIFY COLUMN app_key      VARCHAR(64)  NULL COMMENT '调用方 AppKey',
  MODIFY COLUMN method       VARCHAR(8)   NOT NULL COMMENT 'HTTP 方法：GET/POST',
  MODIFY COLUMN endpoint     VARCHAR(160) NOT NULL COMMENT '归一化接口路径（含新旧前缀统一）',
  MODIFY COLUMN http_status  INT          NOT NULL COMMENT 'HTTP 状态码',
  MODIFY COLUMN biz_code     INT          NULL COMMENT '业务响应码（0 成功，非 0 见错误码表）',
  MODIFY COLUMN success      TINYINT      NOT NULL COMMENT '是否成功：1 成功，0 失败',
  MODIFY COLUMN duration_ms  INT          NOT NULL COMMENT '请求处理耗时（毫秒）',
  MODIFY COLUMN write_count  INT          NOT NULL COMMENT '本次调用打标写入条目数',
  MODIFY COLUMN query_count  INT          NOT NULL COMMENT '本次调用返回的有值标签数',
  MODIFY COLUMN delete_count INT          NOT NULL COMMENT '本次调用作废条目数',
  MODIFY COLUMN operator_id  VARCHAR(64)  NULL COMMENT '操作员标识（写入/作废时取首条）',
  MODIFY COLUMN object_type  VARCHAR(32)  NULL COMMENT '对象类型（取首条）',
  MODIFY COLUMN object_id    VARCHAR(128) NULL COMMENT '对象标识（取首条）',
  MODIFY COLUMN request_id   VARCHAR(64)  NULL COMMENT '请求追踪 ID',
  MODIFY COLUMN client_ip    VARCHAR(64)  NULL COMMENT '调用方 IP',
  MODIFY COLUMN called_at    DATETIME     NOT NULL COMMENT '调用完成时间';

ALTER TABLE tag_access_daily
  COMMENT = '标签访问日统计（租户 + 标签 + 动作按天聚合）',
  MODIFY COLUMN stat_date      DATE        NOT NULL COMMENT '统计日期',
  MODIFY COLUMN tenant_id      VARCHAR(64) NOT NULL COMMENT '租户标识',
  MODIFY COLUMN tag_code       VARCHAR(64) NOT NULL COMMENT '标签编码',
  MODIFY COLUMN action         VARCHAR(16) NOT NULL COMMENT '动作：query-查询，write-打标，delete-作废',
  MODIFY COLUMN access_count   BIGINT      NOT NULL COMMENT '访问次数',
  MODIFY COLUMN last_access_at DATETIME    NOT NULL COMMENT '最近访问时间';
