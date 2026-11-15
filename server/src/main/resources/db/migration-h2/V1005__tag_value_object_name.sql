-- ============================================================================
-- 开放 API 标签推送增加 object_name（对象名称，冗余展示：姓名/车牌/车队名等）
-- latest 与 history 同步扩展；H2 demo 库使用，生产 StarRocks 由
-- StarRocksSchemaInitializer 自动补列
-- ============================================================================

ALTER TABLE tag_value_latest ADD COLUMN IF NOT EXISTS object_name VARCHAR(256);
ALTER TABLE tag_value_history ADD COLUMN IF NOT EXISTS object_name VARCHAR(256);
