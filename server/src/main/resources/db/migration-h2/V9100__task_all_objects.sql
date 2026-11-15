-- ============================================================================
-- 打标任务「全部对象」模式（H2 demo 库）
-- 与 MySQL/OceanBase 的 V9014__task_all_objects.sql 对应
-- ============================================================================

ALTER TABLE tag_task ADD COLUMN IF NOT EXISTS all_objects TINYINT NOT NULL DEFAULT 0;
