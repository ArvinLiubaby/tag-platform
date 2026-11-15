-- ============================================================================
-- V9013 补录「人员基础属性 / 人口特征」3 个标签（年龄 / 性别 / 驾龄）
-- 由 data/gen-missing-tags-1012.py 生成，请勿手工修改
--   取值按清单「判断逻辑」的精算分组枚举化；优先级取「人工打标签标签」=4
-- ============================================================================

-- ---------- 年龄（RK001 → driver_age） ----------
INSERT INTO tag_meta
  (tag_code, tag_name, category_code, dim1_code, dim2_code, priority, value_type, value_range,
   value_multiple, tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status,
   version, object_type, source_mode, writable, created_at, updated_at)
VALUES
  ('driver_age', '年龄', 'demographic', 'baseline', 'demographic', 4, 'enum', '{"enum":["25岁以下","25-29岁","30-39岁","40-59岁","60岁以上","未知"]}',
   0, 'fact', 0, 0, 'risk_control', 'L2', 'online', 1, 'driver', 'manual', 1, NOW(), NOW());
INSERT INTO tag_meta_version
  (tag_code, version, change_type, change_by, change_summary, tag_name, category_code,
   value_type, value_range, value_multiple, tag_type, need_value_audit, value_ttl_days,
   tenant_id, sensitivity, object_type, source_mode, writable, dim1_code, dim2_code, priority,
   created_at)
VALUES
  ('driver_age', 1, 'create', 'import', '清单-1012 补录：RK001 年龄',
   '年龄', 'demographic', 'enum', '{"enum":["25岁以下","25-29岁","30-39岁","40-59岁","60岁以上","未知"]}', 0, 'fact', 0, 0,
   'risk_control', 'L2', 'driver', 'manual', 1, 'baseline', 'demographic', 4, NOW());
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driver_age', 'show_screen', '主', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driver_age', 'risk_priority', '4', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driver_age', 'script', '客服话术示例：
客服：““师傅，我看您开车挺稳的，属牛的吧？牛嘛，踏实稳当。””
司机：“不是，属马。”
客服：“属马好，跑起来利索。那您是七零后还是八零后的马呀？跑车有些年头了吧？”
如果司机质疑：
司机：“你问我属相干嘛？”
客服：“没有没有，就是看您开车挺稳的，猜您属牛，牛踏实。猜错了您别介意，不说也没事。咱说回您这条线，最近路况怎么样？”
< 25岁
25岁 <= 年龄 < 30岁
30岁 <= 年龄 < 40岁
40岁 <= 年龄 < 60岁
>=60岁
未知', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();

-- ---------- 性别（RK002 → gender） ----------
INSERT INTO tag_meta
  (tag_code, tag_name, category_code, dim1_code, dim2_code, priority, value_type, value_range,
   value_multiple, tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status,
   version, object_type, source_mode, writable, created_at, updated_at)
VALUES
  ('gender', '性别', 'demographic', 'baseline', 'demographic', 4, 'enum', '{"enum":["男","女","未知"]}',
   0, 'fact', 0, 0, 'risk_control', 'L2', 'online', 1, 'driver', 'manual', 1, NOW(), NOW());
INSERT INTO tag_meta_version
  (tag_code, version, change_type, change_by, change_summary, tag_name, category_code,
   value_type, value_range, value_multiple, tag_type, need_value_audit, value_ttl_days,
   tenant_id, sensitivity, object_type, source_mode, writable, dim1_code, dim2_code, priority,
   created_at)
VALUES
  ('gender', 1, 'create', 'import', '清单-1012 补录：RK002 性别',
   '性别', 'demographic', 'enum', '{"enum":["男","女","未知"]}', 0, 'fact', 0, 0,
   'risk_control', 'L2', 'driver', 'manual', 1, 'baseline', 'demographic', 4, NOW());
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'gender', 'show_screen', '主', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'gender', 'risk_priority', '4', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();

-- ---------- 驾龄（RK003 → driving_years） ----------
INSERT INTO tag_meta
  (tag_code, tag_name, category_code, dim1_code, dim2_code, priority, value_type, value_range,
   value_multiple, tag_type, need_value_audit, value_ttl_days, tenant_id, sensitivity, status,
   version, object_type, source_mode, writable, created_at, updated_at)
VALUES
  ('driving_years', '驾龄', 'demographic', 'baseline', 'demographic', 4, 'enum', '{"enum":["1年以下","1-2年","3年以上","未知"]}',
   0, 'fact', 0, 0, 'risk_control', 'L2', 'online', 1, 'driver', 'manual', 1, NOW(), NOW());
INSERT INTO tag_meta_version
  (tag_code, version, change_type, change_by, change_summary, tag_name, category_code,
   value_type, value_range, value_multiple, tag_type, need_value_audit, value_ttl_days,
   tenant_id, sensitivity, object_type, source_mode, writable, dim1_code, dim2_code, priority,
   created_at)
VALUES
  ('driving_years', 1, 'create', 'import', '清单-1012 补录：RK003 驾龄',
   '驾龄', 'demographic', 'enum', '{"enum":["1年以下","1-2年","3年以上","未知"]}', 0, 'fact', 0, 0,
   'risk_control', 'L2', 'driver', 'manual', 1, 'baseline', 'demographic', 4, NOW());
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driving_years', 'show_screen', '主', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driving_years', 'risk_priority', '4', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();
INSERT INTO tag_ext_attr (tenant_id, subject_type, subject_code, attr_key, attr_value,
  updated_by, created_at, updated_at)
VALUES ('risk_control', 'TAG', 'driving_years', 'script', '客服话术：
客服：“师傅跑这条线挺熟的吧？……那您跑车有些年头了吧？”
司机：“跑了十来年了。”
客服：“哦，那您是哪一年开始跑车的呀？”
客服拿到“开始跑车年份”后，后台按“跑车年份”先落一个初值；如果后续司机提到“我那会儿刚拿本”，再校准
参考精算模型分组
< 1年
1年 <= 驾龄 < 3年
>=3年
未知', 'import', NOW(), NOW())
ON DUPLICATE KEY UPDATE attr_value = VALUES(attr_value), updated_at = NOW();

-- ---------- 摘要 ----------
-- 新增 3 个标签；话术命中：年龄、驾龄；无话术：性别
