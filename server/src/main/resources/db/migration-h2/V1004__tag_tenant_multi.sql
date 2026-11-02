-- ============================================================================
-- V1004 demo(H2) 标签定义多租户适用
-- tenant_id 改为“适用租户”逗号分隔多值，PUBLIC = 全部租户
-- ============================================================================

ALTER TABLE tag_meta ALTER COLUMN tenant_id SET DATA TYPE VARCHAR(512);
COMMENT ON COLUMN tag_meta.tenant_id IS '适用租户（逗号分隔多值，PUBLIC=全部租户）';
