-- ============================================================================
-- 统计指标单位回填（35 个核心标签中的 9 个数值指标）
-- 数值口径来自《标签体系清单-第一阶段-1012》
-- ============================================================================

UPDATE tag_meta SET unit = '小时', updated_at = NOW() WHERE tag_code = 'avg_daily_duration';
UPDATE tag_meta SET unit = '公里', updated_at = NOW() WHERE tag_code = 'avg_daily_mileage';
UPDATE tag_meta SET unit = '天',   updated_at = NOW() WHERE tag_code = 'monthly_driving_days';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_speeding_count';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_severe_speeding_count';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_fatigue_count';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_distraction_count';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_phone_call_count';
UPDATE tag_meta SET unit = '次',   updated_at = NOW() WHERE tag_code = 'avg_smoking_count';
