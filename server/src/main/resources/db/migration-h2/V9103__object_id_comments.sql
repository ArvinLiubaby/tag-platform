-- ============================================================================
-- V9103：对象标识口径澄清后的列注释对齐
--
-- 背景：V6 时代曾把「车牌号」当作车辆对象的唯一标识，建表注释长期停留在
--       「对象唯一标识（司机ID/车牌号/车队ID/事件ID）」；V9017 已澄清口径——
--       车辆的 object_id 是**车辆唯一 ID**（车联网内部编号，如 VH10086），
--       车牌号放 object_name（仅展示，不参与主键/幂等/频控）。
-- 变更：仅修正注释，不改类型与约束。新库由 db/starrocks/schema.sql 直接建出正确注释。
-- 说明：V1002 已执行过，不能回改（Flyway 校验和不一致），故以新迁移覆盖。
-- ============================================================================

COMMENT ON COLUMN tag_value_latest.object_id IS '对象唯一标识（车辆=车辆唯一ID，如 VH10086；司机ID/车队ID/事件ID。车牌号放 object_name）';
COMMENT ON COLUMN tag_value_latest.object_name IS '对象名称（车辆=车牌号；司机姓名/车队名等，可选，仅展示不参与主键）';
COMMENT ON COLUMN tag_value_history.object_id IS '对象唯一标识（车辆=车辆唯一ID，如 VH10086；司机ID/车队ID/事件ID。车牌号放 object_name）';
COMMENT ON COLUMN tag_value_history.object_name IS '对象名称（车辆=车牌号；司机姓名/车队名等，可选，仅展示不参与主键）';
