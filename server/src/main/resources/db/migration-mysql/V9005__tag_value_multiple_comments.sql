-- ============================================================================
-- V9005 标签值选择方式：字段注释（MySQL 8 / OceanBase MySQL 模式）
-- ============================================================================

ALTER TABLE tag_meta
  MODIFY COLUMN value_multiple TINYINT NOT NULL DEFAULT 0
    COMMENT '值选择方式：0-单选，1-多选（仅 enum 标签生效）';
