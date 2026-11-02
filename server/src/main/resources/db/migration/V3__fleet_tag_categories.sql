-- ============================================================================
-- V3 本期上线标签体系（《标签体系清单-1015》）：一级 / 二级维度分类树
-- 说明：
--   - 一级维度 = 分类根节点（parent_code 为 NULL）；二级维度 = 子节点。
--   - 32 个标签由 data/seed-fleet-tags.sh 通过控制台 API 注册并审核上线，
--     以演示「注册 → 定义审核 → 上线」完整流程（可幂等重复执行）。
--   - 来源/实体层级说明见 docs/tag-catalog-1015.md。
-- ============================================================================

INSERT INTO tag_category (category_code, category_name, parent_code, sort_no) VALUES
-- 一级维度
('personality',         '性格特征',       NULL, 100),
('driving_ops',         '驾驶与运营习惯', NULL, 200),
('driving_behavior',    '驾驶行为',       NULL, 300),
('service_interaction', '服务交互表现',   NULL, 400),
('operation_mode',      '运营模式',       NULL, 500),
-- 二级维度 · 性格特征
('personality_trait',   '性格特质', 'personality',         101),
('emotion_trait',       '情绪特质', 'personality',         102),
('risk_attitude',       '风险态度', 'personality',         103),
('physio_feature',      '生理特征', 'personality',         104),
('language_feature',    '语言特征', 'personality',         105),
-- 二级维度 · 驾驶与运营习惯
('driving_habit',       '驾驶习惯', 'driving_ops',         201),
('operation_habit',     '运营习惯', 'driving_ops',         202),
-- 二级维度 · 驾驶行为
('speeding',            '超速',     'driving_behavior',    301),
('fatigue',             '疲劳',     'driving_behavior',    302),
('distraction',         '分心',     'driving_behavior',    303),
-- 二级维度 · 服务交互表现
('communication',       '沟通',     'service_interaction', 401),
-- 二级维度 · 运营模式
('operating_attr',      '营运属性', 'operation_mode',      501),
('ownership',           '车辆归属', 'operation_mode',      502),
('driver_relation',     '司机关系', 'operation_mode',      503),
('cargo_source',        '货源来源', 'operation_mode',      504),
('transport_scope',     '运输范围', 'operation_mode',      505),
('cargo_type',          '货物类型', 'operation_mode',      506),
('industry_type',       '行业类型', 'operation_mode',      507),
('work_env',            '作业环境', 'operation_mode',      508);
