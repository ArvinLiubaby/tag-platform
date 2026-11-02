-- ============================================================================
-- 置信度语义调整：数值(0~1) → 枚举字符串（高/中/低）
--
-- 背景：tag_value_latest / tag_value_history 的 confidence 由 FLOAT/DOUBLE
--       改为 VARCHAR(10)，写入契约同步改为「高 / 中 / 低」。
-- 存量映射：>= 0.8 高；>= 0.5 中；其余低；NULL 保持 NULL。
-- 注意：本迁移只改 demo(H2) 库；StarRocks 需执行
--       deploy/starrocks/migrate-v10-confidence.sql。
-- ============================================================================

ALTER TABLE tag_value_latest ALTER COLUMN confidence SET DATA TYPE VARCHAR(10);
ALTER TABLE tag_value_history ALTER COLUMN confidence SET DATA TYPE VARCHAR(10);

UPDATE tag_value_latest SET confidence = CASE
    WHEN confidence IS NULL OR confidence = '' THEN NULL
    WHEN CAST(confidence AS DOUBLE) >= 0.8 THEN '高'
    WHEN CAST(confidence AS DOUBLE) >= 0.5 THEN '中'
    ELSE '低'
END;

UPDATE tag_value_history SET confidence = CASE
    WHEN confidence IS NULL OR confidence = '' THEN NULL
    WHEN CAST(confidence AS DOUBLE) >= 0.8 THEN '高'
    WHEN CAST(confidence AS DOUBLE) >= 0.5 THEN '中'
    ELSE '低'
END;

COMMENT ON COLUMN tag_value_latest.confidence IS '置信度：高/中/低（算法标签使用）';
COMMENT ON COLUMN tag_value_history.confidence IS '置信度：高/中/低（算法标签使用）';
