-- ============================================================================
-- V9008 标签定义版本管理
-- 每次注册/修改定义/切换写入开关/回滚都保存一份完整定义快照；
-- 上下线、归档等生命周期操作不生成定义版本，仍由审计日志记录。
-- 兼容：MySQL 8 / OceanBase MySQL 模式 / H2(MySQL 模式)
-- ============================================================================

CREATE TABLE tag_meta_version (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  tag_code         VARCHAR(64)  NOT NULL,
  version          INT          NOT NULL,
  change_type      VARCHAR(16)  NOT NULL,
  change_by        VARCHAR(128) NOT NULL,
  change_summary   VARCHAR(512),
  tag_name         VARCHAR(128) NOT NULL,
  category_code    VARCHAR(64),
  value_type       VARCHAR(16)  NOT NULL,
  value_range      TEXT,
  value_multiple   TINYINT      NOT NULL,
  tag_type         VARCHAR(16)  NOT NULL,
  need_value_audit TINYINT      NOT NULL,
  value_ttl_days   INT          NOT NULL,
  tenant_id        VARCHAR(512) NOT NULL,
  sensitivity      VARCHAR(8)   NOT NULL,
  object_type      VARCHAR(64),
  source_mode      VARCHAR(16),
  writable         TINYINT      NOT NULL,
  created_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tag_code, version)
);
CREATE INDEX idx_tag_meta_version_code ON tag_meta_version (tag_code, version);

-- 存量标签建立基线快照：基线版本 = 当前 tag_meta.version
INSERT INTO tag_meta_version (tag_code, version, change_type, change_by, change_summary,
                              tag_name, category_code, value_type, value_range, value_multiple,
                              tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity,
                              object_type, source_mode, writable, created_at)
SELECT tag_code, version, 'baseline', 'system', '版本管理启用：当前定义基线',
       tag_name, category_code, value_type, value_range, value_multiple,
       tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity,
       object_type, source_mode, writable, updated_at
  FROM tag_meta;
