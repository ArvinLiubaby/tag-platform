-- ============================================================================
-- V9010 标签组合（派生标签）：
--   组合规则与派生标签定义 1:1；派生标签 tag_meta.tag_type = 'derived'、
--   writable = 0、source_mode = 'derived'，不参与开放写入，值由查询期实时计算。
--   规则随定义版本一起快照到 tag_meta_version.composition_json，支持回滚。
-- 兼容：MySQL 8 / OceanBase MySQL 模式 / H2(MySQL 模式)
-- ============================================================================

CREATE TABLE tag_composition (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  tag_code         VARCHAR(64)  NOT NULL,
  tenant_id        VARCHAR(512) NOT NULL,
  object_type      VARCHAR(32)  NOT NULL,
  logic            VARCHAR(8)   NOT NULL,
  conditions_json  TEXT         NOT NULL,
  output_type      VARCHAR(16)  NOT NULL,
  output_json      TEXT         NOT NULL,
  missing_policy   VARCHAR(16)  NOT NULL,
  description      VARCHAR(512),
  status           VARCHAR(16)  NOT NULL,
  rule_version     INT          NOT NULL,
  created_by       VARCHAR(128) NOT NULL,
  updated_by       VARCHAR(128),
  created_at       DATETIME     NOT NULL,
  updated_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tag_code)
);
CREATE INDEX idx_tag_composition_status ON tag_composition (status);
CREATE INDEX idx_tag_composition_object ON tag_composition (object_type, status);

ALTER TABLE tag_meta_version ADD COLUMN composition_json TEXT;
