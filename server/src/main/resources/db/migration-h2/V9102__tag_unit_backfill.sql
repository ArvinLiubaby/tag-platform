-- 统计指标单位回填（H2 demo 库，与 V9016__tag_unit_backfill.sql 对应）
UPDATE tag_meta SET unit = '小时', updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_daily_duration';
UPDATE tag_meta SET unit = '公里', updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_daily_mileage';
UPDATE tag_meta SET unit = '天',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'monthly_driving_days';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_speeding_count';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_severe_speeding_count';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_fatigue_count';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_distraction_count';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_phone_call_count';
UPDATE tag_meta SET unit = '次',   updated_at = CURRENT_TIMESTAMP WHERE tag_code = 'avg_smoking_count';
