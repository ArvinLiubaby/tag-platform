-- ============================================================================
-- V8：车辆标识字段定名为 vehicle_plate（取值为车牌号）
--
-- 背景：V7 曾将车辆标识统一为 vehicle_id；实际语义是车牌号，命名为
--       vehicle_plate 更直观，且与 driver_id 一起构成查询接口的类型化标识字段。
-- 影响：object_type_meta.vehicle.id_field 由 vehicle_id 调整为 vehicle_plate；
--       校验规则（车牌号格式）不变；写接口仍使用 object_type=vehicle + object_id=<车牌号>。
-- ============================================================================
UPDATE object_type_meta
SET id_field = 'vehicle_plate'
WHERE object_type = 'vehicle';
