-- ============================================================================
-- V7：车辆标识字段统一命名为 vehicle_id（取值为车牌号）
--
-- 背景：开放 API 的司机/车辆标识字段需保持同一命名口径（<object_type>_id）。
--       组合查询参数由 plate_no 统一为 vehicle_id，值仍为车牌号。
-- 影响：object_type_meta.vehicle.id_field 由 plate_no 调整为 vehicle_id；
--       校验规则（车牌号格式）不变；写接口仍使用 object_type=vehicle + object_id=<车牌号>。
-- ============================================================================
UPDATE object_type_meta
SET id_field = 'vehicle_id'
WHERE object_type = 'vehicle';
