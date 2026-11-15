-- 统计指标单位（H2 demo 库，与 V9015__tag_unit.sql 对应）
ALTER TABLE tag_meta ADD COLUMN IF NOT EXISTS unit VARCHAR(16);
ALTER TABLE tag_meta_version ADD COLUMN IF NOT EXISTS unit VARCHAR(16);
