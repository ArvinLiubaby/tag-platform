-- ============================================================================
-- V4 标签-对象绑定与打标来源类型（tag_meta 字典扩展）
--   object_type：适用对象类型，逗号分隔多值（如 driver,vehicle）；
--                写入时强制校验：标签声明的对象类型必须包含请求的 object_type；
--                NULL/空串 = 不限（兼容仅靠约定绑定的历史用法）。
--   source_mode：打标来源类型 manual(客服手工) / statistics(数据统计)
--                / hybrid(手工+统计混合) / algo(算法模型)；NULL = 未声明。
-- 回填范围：V2 种子标签 + 《标签体系清单-1015》32 个标签（分类树见 V3）。
-- ============================================================================

ALTER TABLE tag_meta ADD COLUMN object_type VARCHAR(64);
ALTER TABLE tag_meta ADD COLUMN source_mode VARCHAR(16);

-- ---- V2 种子标签回填（按演示脚本/冒烟用例的实际使用对象） ----
UPDATE tag_meta SET object_type = 'driver'  WHERE tag_code IN ('suspected_fraud', 'risk_level', 'alarm_count_30d', 'driver_score');
UPDATE tag_meta SET object_type = 'vehicle' WHERE tag_code IN ('plate_blacklist', 'oil_level', 'night_driving', 'high_mileage');
UPDATE tag_meta SET object_type = 'fleet'   WHERE tag_code = 'fleet_risk';

UPDATE tag_meta SET source_mode = 'manual'     WHERE tag_code IN ('suspected_fraud', 'plate_blacklist');
UPDATE tag_meta SET source_mode = 'statistics' WHERE tag_code IN ('alarm_count_30d', 'oil_level', 'night_driving', 'high_mileage');
UPDATE tag_meta SET source_mode = 'hybrid'     WHERE tag_code = 'risk_level';
UPDATE tag_meta SET source_mode = 'algo'       WHERE tag_code IN ('driver_score', 'fleet_risk');

-- ---- 《标签体系清单-1015》32 个标签回填（对尚未注册该批标签的库为 0 行，无副作用） ----
-- 司机 17 个（doc 见 docs/tag-catalog-1015.md 第 3 章）
UPDATE tag_meta SET object_type = 'driver' WHERE tag_code IN (
  'social_tendency', 'emotion_stability', 'driving_risk_attitude', 'eye_size', 'stutter_flag',
  'language_style', 'monthly_driving_days', 'avg_speeding_count', 'avg_severe_speeding_count',
  'avg_fatigue_count', 'avg_distraction_count', 'avg_smoking_count', 'avg_phone_call_count',
  'phone_reachability', 'comm_attitude', 'expression_clarity', 'driver_relation');
-- 车辆 15 个
UPDATE tag_meta SET object_type = 'vehicle' WHERE tag_code IN (
  'route_type_pref', 'route_stability', 'operating_time_pref', 'avg_daily_mileage',
  'avg_daily_duration', 'operating_region_level', 'long_haul', 'vehicle_operating_attr',
  'vehicle_operating_type', 'vehicle_ownership', 'cargo_source', 'transport_scope',
  'cargo_type', 'industry_type', 'work_environment');

-- 来源类型：纯客服手工 6 / 纯数据统计 9 / 手工+统计混合 17（D 列口径）
UPDATE tag_meta SET source_mode = 'manual' WHERE tag_code IN (
  'eye_size', 'stutter_flag', 'language_style',
  'phone_reachability', 'comm_attitude', 'expression_clarity');
UPDATE tag_meta SET source_mode = 'statistics' WHERE tag_code IN (
  'avg_daily_mileage', 'avg_daily_duration', 'monthly_driving_days',
  'avg_speeding_count', 'avg_severe_speeding_count', 'avg_fatigue_count',
  'avg_distraction_count', 'avg_smoking_count', 'avg_phone_call_count');
UPDATE tag_meta SET source_mode = 'hybrid' WHERE tag_code IN (
  'social_tendency', 'emotion_stability', 'driving_risk_attitude',
  'route_type_pref', 'route_stability', 'operating_time_pref', 'operating_region_level', 'long_haul',
  'vehicle_operating_attr', 'vehicle_operating_type', 'vehicle_ownership', 'driver_relation',
  'cargo_source', 'transport_scope', 'cargo_type', 'industry_type', 'work_environment');
