-- ============================================================================
-- V9012 标签字典数据（《标签体系清单-第一阶段-1012-含话术.xlsx》）
-- 由 data/import-tag-dict-1012.py 生成，请勿手工修改
--   1) 回填 32 个存量标签的一级/二级维度、优先级
--   2) 写入租户级扩展信息：展示屏幕 / 标签话术 / 风控展示优先级
-- 说明：清单中「年龄/性别/驾龄」3 个标签库内尚未注册，本次跳过
-- ============================================================================

-- ---------- 1. 标签维度 / 优先级 ----------
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'personality_trait', priority = 3, updated_at = NOW() WHERE tag_code = 'social_tendency';
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'emotion_trait', priority = 3, updated_at = NOW() WHERE tag_code = 'emotion_stability';
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'risk_attitude', priority = 3, updated_at = NOW() WHERE tag_code = 'driving_risk_attitude';
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'physio_feature', priority = 1, updated_at = NOW() WHERE tag_code = 'eye_size';
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'language_feature', priority = 1, updated_at = NOW() WHERE tag_code = 'stutter_flag';
UPDATE tag_meta SET dim1_code = 'personality', dim2_code = 'language_feature', priority = 1, updated_at = NOW() WHERE tag_code = 'language_style';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'driving_habit', priority = 7, updated_at = NOW() WHERE tag_code = 'route_type_pref';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'driving_habit', priority = 7, updated_at = NOW() WHERE tag_code = 'route_stability';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = 7, updated_at = NOW() WHERE tag_code = 'operating_time_pref';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_daily_mileage';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_daily_duration';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = NULL, updated_at = NOW() WHERE tag_code = 'monthly_driving_days';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = 8, updated_at = NOW() WHERE tag_code = 'operating_region_level';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'operation_habit', priority = 8, updated_at = NOW() WHERE tag_code = 'long_haul';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'speeding', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_speeding_count';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'speeding', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_severe_speeding_count';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'fatigue', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_fatigue_count';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'distraction', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_distraction_count';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'distraction', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_smoking_count';
UPDATE tag_meta SET dim1_code = 'driving_ops', dim2_code = 'distraction', priority = NULL, updated_at = NOW() WHERE tag_code = 'avg_phone_call_count';
UPDATE tag_meta SET dim1_code = 'service_interaction', dim2_code = 'communication', priority = 2, updated_at = NOW() WHERE tag_code = 'phone_reachability';
UPDATE tag_meta SET dim1_code = 'service_interaction', dim2_code = 'communication', priority = 2, updated_at = NOW() WHERE tag_code = 'comm_attitude';
UPDATE tag_meta SET dim1_code = 'service_interaction', dim2_code = 'communication', priority = 2, updated_at = NOW() WHERE tag_code = 'expression_clarity';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'operating_attr', priority = 4, updated_at = NOW() WHERE tag_code = 'vehicle_operating_attr';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'operating_attr', priority = 5, updated_at = NOW() WHERE tag_code = 'vehicle_operating_type';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'ownership', priority = 5, updated_at = NOW() WHERE tag_code = 'vehicle_ownership';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'driver_relation', priority = 5, updated_at = NOW() WHERE tag_code = 'driver_relation';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'cargo_source', priority = 5, updated_at = NOW() WHERE tag_code = 'cargo_source';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'transport_scope', priority = 6, updated_at = NOW() WHERE tag_code = 'transport_scope';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'cargo_type', priority = 6, updated_at = NOW() WHERE tag_code = 'cargo_type';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'industry_type', priority = 6, updated_at = NOW() WHERE tag_code = 'industry_type';
UPDATE tag_meta SET dim1_code = 'operation_mode', dim2_code = 'work_env', priority = 6, updated_at = NOW() WHERE tag_code = 'work_environment';

-- ---------- 2. 一级维度优先级 ----------
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'DIM1', 'personality', 'dim1_priority', '1', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'DIM1', 'driving_ops', 'dim1_priority', '2', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'DIM1', 'service_interaction', 'dim1_priority', '3', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'DIM1', 'operation_mode', 'dim1_priority', '4', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'DIM1', 'baseline', 'dim1_priority', '5', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();

-- ---------- 3. 标签扩展信息（展示屏幕 / 话术 / 风控展示优先级） ----------
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'social_tendency', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'social_tendency', 'risk_priority', '3', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'emotion_stability', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'emotion_stability', 'risk_priority', '3', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driving_risk_attitude', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driving_risk_attitude', 'risk_priority', '3', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driving_risk_attitude', 'script', '客服话术：“师傅，想跟您了解一下平时开车的习惯，方便我们做安全提醒。您平时开车是偏稳当，还是有时候赶时间会开得比较快？”
客服根据以下回答追问：
司机回答 | 客服追问
稳当为主 | “那急加速、急刹车这种多不多？”
有时候赶时间 | “赶时间会不会经常变道、超车？”
开得比较快 | “高速上一般压着限速跑，还是偶尔超一点？”
说不清 | “那您觉得自己开车算稳的还是猛的？”
客服再补一句：“晚上开夜车会不会比白天快？跟车距离一般近不近？”
页面选项 | 后台取值
稳当为主，很少急操作 | 保守
正常，偶尔赶时间 | 中性
偏激烈、经常赶时间、超速、急操作四项满足其一即可 | 激进
说不清/不承认 | 待定（以数据统计为准）', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'eye_size', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'eye_size', 'risk_priority', '1', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'stutter_flag', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'stutter_flag', 'risk_priority', '1', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'language_style', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'language_style', 'risk_priority', '1', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_type_pref', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_type_pref', 'risk_priority', '7', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_type_pref', 'script', '客服话术：“师傅，您平时跑车主要走哪类路？是高速多，还是市区道路多，还是国道省道多，还是县道或者乡公路较多，还是其它道路？” 如果司机说“都跑”，追问一句：“那您哪类路走得最多？”如果司机说“说不清”，选“待定”或“无明显偏好”。
页面选项 | 后台取值
高速为主 | 高速为主
国省道为主 | 国省道为主
县道或乡公路 | 县乡道路（县道+乡公路）为主
其它道路 | 其它道路
都差不多，无明显偏好 | 无明显偏好
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_stability', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_stability', 'risk_priority', '7', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'route_stability', 'script', '客服话术：“师傅，您平时跑的路线固定吗？是每天基本跑同一条线，还是有几条常跑的线但会换，还是到处跑没个准？” 如果司机说“有几条常跑的”，追问：“那您主要跑这几条，还是经常换新路线？”
页面选项 | 后台取值
基本固定，每天跑同一条线 | 固定
有几条常跑的线，偶尔换 | 灵活
没有固定路线，到处跑 | 散跑
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_time_pref', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_time_pref', 'risk_priority', '7', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_time_pref', 'script', '客服话术：“师傅，您平时出车主要是在白天跑，还是晚上跑？如果晚上跑，一般是前半夜（晚上10点到凌晨2点）多，还是后半夜（凌晨2点到6点）多？还是白天晚上都跑？” 如果司机说“都跑”，追问一句：“那您哪个时段跑得最多？”如果司机说“说不清”，选“无明显偏好”或“待定”。
页面选项 | 后台取值
白天为主 | 日间为主
晚上10点到凌晨2点为主 | 前夜间（22-02）惯跑
凌晨2点到6点为主 | 后半夜（02-06）惯跑
白天晚上都跑，比较均匀 | 全天候
没有固定时段，说不清 | 无明显偏好
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_daily_mileage', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_daily_duration', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'monthly_driving_days', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_region_level', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_region_level', 'risk_priority', '8', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'operating_region_level', 'script', '客服话术：“师傅，您平时跑车的范围大概有多大？是只在一个城市里跑，还是跑遍全省，还是经常跨省跑，还是全国各地都跑？”  如果司机说“到处跑”，追问一句：“那您主要是跑周边几个省，还是全国都跑？”如果司机说“说不清”，选“待定”。
页面选项 | 后台取值
只在一个城市内跑 | 同城
主要在省内跑 | 省内
经常跨省跑 | 跨省
全国各地都跑 | 全国
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'long_haul', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'long_haul', 'risk_priority', '8', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_speeding_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_severe_speeding_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_fatigue_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_distraction_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_smoking_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'avg_phone_call_count', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'phone_reachability', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'phone_reachability', 'risk_priority', '2', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'comm_attitude', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'comm_attitude', 'risk_priority', '2', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'expression_clarity', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'expression_clarity', 'risk_priority', '2', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_operating_attr', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_operating_attr', 'risk_priority', '4', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_operating_type', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_operating_type', 'risk_priority', '5', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_ownership', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_ownership', 'risk_priority', '5', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'vehicle_ownership', 'script', '客服话术：“师傅，这车是您自己的吗？” → 如果不是，再问“是公司的，还是租的/第三方派的？”
页面选项 | 后台标签取值
公司的 | 公司车
我自己的 | 自有车（或“自己的”）
别人的 | 他人车（或“别人的”）
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driver_relation', 'show_screen', '主', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driver_relation', 'risk_priority', '5', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'driver_relation', 'script', '客服话术：师傅，您跟公司是什么关系？是公司员工给您发工资，还是您给公司交钱，还是自己干，还是第三方公司派来的？
页面选项 | 后台标签取值
公司员工，发工资 | 公司员工
我给公司交钱 | 承包（如果车辆归属=公司车）或 承租（如果车辆归属=他人车）
我自己干 | 自己干
第三方公司派来的 | 第三方派遣
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_source', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_source', 'risk_priority', '5', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_source', 'script', '客服话术：“师傅，您平时的活儿主要从哪儿来？是公司给您安排，还是您自己找，还是通过平台接单？还是几种都有？”
如果司机说“都有”，追问一句：“那哪种最多？”
页面选项 | 后台标签取值
公司安排 | 公司安排
自己找 | 自己找
平台接单 | 平台接单
都有（混合） | 混合
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'transport_scope', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'transport_scope', 'risk_priority', '6', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'transport_scope', 'script', '客服话术：“师傅，您平时主要跑什么范围？
是跑长途跨省跨市，
还是跑周边城市、县城，
还是只在市区内送货，
还是在港口、园区里短途倒运？
还是不固定？” 如果司机说“都有”，追问一句：“那哪种最多？”
页面选项 | 后台取值
长途跨省/跨市 | 干线
跑周边城市/县城 | 支线
只在市区内送货 | 城配
港口/园区/矿区内倒短 | 短途倒运
不固定/都有 | 无固定
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_type', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_type', 'risk_priority', '6', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'cargo_type', 'script', '客服话术：“师傅，您平时主要拉什么货？是普通货，还是需要冷藏冷冻的，还是工程材料，还是其他？”如果司机说“都有”，追问一句：“那您拉得最多的是哪种？”
页面选项 | 后台取值
普通货物 | 普货
冷藏冷冻货物 | 冷链
工程材料 | 工程材料
其他 | 其他
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'industry_type', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'industry_type', 'risk_priority', '6', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'industry_type', 'script', '客服话术：”师傅，您平时主要拉什么活？是给人开公交、跑客运，还是送快递快运，还是在工地拉渣土、砂石这些工程活，还是其他？“如果司机说“都干”，追问一句：“那您干得最多的是哪种？”
页面选项 | 后台取值
开公交/跑客运 | 公交客运
送快递/快运 | 快递快运
工地工程活 | 工程运输
其他 | 其他
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'work_environment', 'show_screen', '副', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'work_environment', 'risk_priority', '6', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value, updated_by, created_at, updated_at) VALUES ('risk_control', 'TAG', 'work_environment', 'script', '客服话术：“师傅，您平时主要在哪里干活？是在外面跑公共道路，还是在港口、矿区、园区、工地里面干活？” 如果司机说“都跑”，追问一句：“那您主要在哪儿干得最多？”
页面选项 | 后台取值
公共道路 | 公共道路
港口 | 港口
矿区 | 矿区
园区 | 园区
工地 | 工地
其他 | 其他
说不清 | 待定', 'import', NOW(), NOW()) ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();

-- ---------- 导入摘要 ----------
-- 标签更新 32 条；扩展值 72 条；话术命中 12 个
-- 未导入 3 条：年龄、性别、驾龄
