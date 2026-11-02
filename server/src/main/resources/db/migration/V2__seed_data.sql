-- ============================================================================
-- V2 演示种子数据（租户 / 凭证 / 类目 / 标签 / 对象类型 / 控制台用户）
-- 说明：Secret 与初始密码为演示用明文占位（应用按 "非 enc:v1: 前缀视为明文" 兼容读取）；
--      正式环境请通过控制台"重置密钥/重置密码"生成加密存储值。
-- ============================================================================

INSERT INTO tenant (tenant_id, tenant_name, access_system, status, write_qps, read_qps, created_at, updated_at) VALUES
('risk_control', '风控平台', '客服打标系统', 'enabled', 2000, 5000, '2026-10-01 09:00:00', '2026-10-01 09:00:00'),
('system_a', '业务系统 A', '标签写入 · 查询', 'enabled', 1000, 3000, '2026-10-05 14:00:00', '2026-10-05 14:00:00');

INSERT INTO app_credential (tenant_id, app_key, secret_enc, status, created_at) VALUES
('risk_control', 'AK_RC_8f2a91', 'rc-secret-8f2a91-demo', 'enabled', '2026-10-01 09:00:00'),
('system_a', 'AK_SA_3c91d2', 'sa-secret-3c91d2-demo', 'enabled', '2026-10-05 14:00:00');

INSERT INTO tag_category (category_code, category_name, parent_code, sort_no) VALUES
('risk_qual', '风控 / 定性', NULL, 10),
('risk_score', '风控 / 评分', NULL, 20),
('behavior_fact', '行为 / 事实', NULL, 30),
('vehicle_fact', '车况 / 事实', NULL, 40);

INSERT INTO tag_meta (tag_code, tag_name, category_code, value_type, value_range, tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status, version, created_at, updated_at) VALUES
('suspected_fraud',  '疑似骗保',   'risk_qual',     'boolean', NULL,                                'rule',    1, 30, 'risk_control', 'L3', 'online',    1, '2026-10-08 10:00:00', '2026-10-08 10:00:00'),
('plate_blacklist',  '车牌黑名单', 'risk_qual',     'boolean', NULL,                                'rule',    1, 0,  'risk_control', 'L3', 'online',    1, '2026-10-08 10:05:00', '2026-10-08 10:05:00'),
('risk_level',       '风险等级',   'risk_score',    'enum',    '{"enum":["low","medium","high"]}',  'rule',    0, 0,  'risk_control', 'L2', 'online',    1, '2026-10-08 10:10:00', '2026-10-08 10:10:00'),
('alarm_count_30d',  '30 日告警数','risk_score',    'number',  '{"min":0,"max":999999}',            'rule',    0, 7,  'risk_control', 'L2', 'online',    1, '2026-10-08 10:15:00', '2026-10-08 10:15:00'),
('oil_level',        '当前油箱液位','vehicle_fact', 'number',  '{"min":0,"max":200}',               'fact',    0, 1,  'system_a',     'L2', 'online',    1, '2026-10-09 09:00:00', '2026-10-09 09:00:00'),
('night_driving',    '夜间行驶',   'behavior_fact', 'boolean', NULL,                                'fact',    0, 1,  'system_a',     'L2', 'online',    1, '2026-10-09 09:05:00', '2026-10-09 09:05:00'),
('high_mileage',     '高里程',     'behavior_fact', 'number',  '{"min":0,"max":9999999}',           'fact',    0, 0,  'PUBLIC',       'L1', 'online',    1, '2026-10-09 09:10:00', '2026-10-09 09:10:00'),
('driver_score',     '司机评分',   'risk_score',    'number',  '{"min":0,"max":100}',               'algo',    0, 30, 'PUBLIC',       'L2', 'online',    1, '2026-10-09 09:15:00', '2026-10-09 09:15:00'),
('fleet_risk',       '车队风险等级','risk_score',   'enum',    '{"enum":["low","medium","high"]}',  'algo',    0, 30, 'PUBLIC',       'L2', 'reviewing', 1, '2026-10-12 11:00:00', '2026-10-12 11:00:00');

INSERT INTO tag_audit (tag_code, action_type, submit_by, audit_by, audit_remark, status, created_at) VALUES
('suspected_fraud',  'register', '风控平台', '平台管理员', '命名规范校验通过，敏感级别 L3', 'approved', '2026-10-08 09:56:00'),
('plate_blacklist',  'register', '风控平台', '平台管理员', '命名规范校验通过，敏感级别 L3', 'approved', '2026-10-08 10:02:00'),
('fleet_risk',       'register', '数据算法组', NULL, NULL, 'reviewing', '2026-10-12 11:00:00');

INSERT INTO object_type_meta (object_type, type_name, id_field, id_pattern, object_class, default_ttl, status) VALUES
('driver',      '司机',     'driver_id', '^DRV[0-9]{5,}$',                    'entity', 0,  'enabled'),
('vehicle',     '车辆',     'vin',       '^[A-HJ-NPR-Z0-9]{17}$',            'entity', 0,  'enabled'),
('fleet',       '车队',     'fleet_id',  '^FLT[0-9]{4,}$',                   'entity', 0,  'enabled'),
('alarm_event', '告警事件', 'event_id',  '^EVT[0-9A-Z]{6,}$',                'event',  90, 'enabled');

INSERT INTO quota (tenant_id, quota_dim, limit_value, used_value, updated_at) VALUES
('risk_control', 'write_qps', 2000, 0, '2026-10-14 00:00:00'),
('risk_control', 'read_qps',  5000, 0, '2026-10-14 00:00:00'),
('system_a',     'write_qps', 1000, 0, '2026-10-14 00:00:00'),
('system_a',     'read_qps',  3000, 0, '2026-10-14 00:00:00');

INSERT INTO console_user (username, password_hash, display_name, user_role, status, created_at) VALUES
('admin',     'plain:admin123',  '平台管理员', 'ADMIN',   'enabled', '2026-10-01 09:00:00'),
('auditor1',  'plain:audit123',  '风控审核员', 'AUDITOR', 'enabled', '2026-10-01 09:00:00'),
('viewer1',   'plain:viewer123', '只读观察员', 'VIEWER',  'enabled', '2026-10-01 09:00:00');
