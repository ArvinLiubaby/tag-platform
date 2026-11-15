-- ============================================================================
-- V9019：对象类型元数据的「唯一标识字段」对齐车辆口径
--
-- 背景：V6 时代 object_type_meta.vehicle.id_field 被登记为 vehicle_plate，
--       即把车牌号当作车辆主键；V9017 已澄清：object_id 用车辆唯一 ID，
--       车牌号放 object_name。V9017 只放宽了 id_pattern，未修 id_field，
--       导致查 object_type_meta 时仍看到「vehicle_plate」这一过时口径。
-- 影响：id_field 仅作元数据说明（代码未用于校验/路由），修正无功能影响。
-- ============================================================================

UPDATE object_type_meta
   SET id_field = 'vehicle_id'
 WHERE object_type = 'vehicle'
   AND id_field = 'vehicle_plate';
