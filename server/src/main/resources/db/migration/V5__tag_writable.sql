-- ============================================================================
-- V5 标签写入开关（tag_meta.writable）
--   writable = 1 允许写入（默认）：开放 API 可推送/作废该标签的值
--   writable = 0 拒绝写入：POST /open/v1/tag-values/write|delete 整批拒绝（code=3012）
-- 已有标签全部回填为 1（DEFAULT），升级后行为与升级前完全一致。
-- 控制台可通过 POST /console/api/tags/{tagCode}/writable 切换开关。
-- ============================================================================

ALTER TABLE tag_meta ADD COLUMN writable TINYINT NOT NULL DEFAULT 1;
