-- ============================================================================
-- V9011 标签字典增强（《标签体系清单-第一阶段-1012-含话术》）：
--   1. tag_meta 增加一级维度、二级维度、标签优先级（人工打标顺序）
--   2. 分类树按新口径调整：超速/疲劳/分心归入「驾驶与运营习惯」；
--      新增「人员基础属性 → 人口特征」一级/二级维度
--   3. 租户级标签扩展信息：属性定义（可增减）+ 属性值（KV）
--      当前内置 5 个：展示屏幕、是否隐藏、标签话术、风控展示优先级、
--      一级维度优先级（subject_type=DIM1）
-- 兼容：MySQL 8 / OceanBase MySQL 模式 / H2(MySQL 模式)
-- ============================================================================

-- ---------- 1. 标签定义扩展 ----------
-- 一级维度编码（对应 tag_category 中 parent_code 为 NULL 的节点）
ALTER TABLE tag_meta ADD COLUMN dim1_code VARCHAR(64);
-- 二级维度编码（与 category_code 同值，显式存储便于按维度过滤/导出）
ALTER TABLE tag_meta ADD COLUMN dim2_code VARCHAR(64);
-- 标签优先级：来源《标签体系清单》「人工打标签标签」列，数值越小越靠前，空值=非人工打标标签
ALTER TABLE tag_meta ADD COLUMN priority INT;

-- 定义版本快照同步扩展，保证回滚可恢复维度与优先级
ALTER TABLE tag_meta_version ADD COLUMN dim1_code VARCHAR(64);
ALTER TABLE tag_meta_version ADD COLUMN dim2_code VARCHAR(64);
ALTER TABLE tag_meta_version ADD COLUMN priority INT;

-- ---------- 2. 分类树调整（新口径） ----------
-- 2.1 超速 / 疲劳 / 分心：由「驾驶行为」移入「驾驶与运营习惯」
UPDATE tag_category SET parent_code = 'driving_ops', sort_no = 203 WHERE category_code = 'speeding';
UPDATE tag_category SET parent_code = 'driving_ops', sort_no = 204 WHERE category_code = 'fatigue';
UPDATE tag_category SET parent_code = 'driving_ops', sort_no = 205 WHERE category_code = 'distraction';
-- 2.2 原「驾驶行为」一级节点已无子节点，移除（标签均挂在二级维度上）
DELETE FROM tag_category WHERE category_code = 'driving_behavior';
-- 2.3 新增「人员基础属性 → 人口特征」
INSERT INTO tag_category (category_code, category_name, parent_code, sort_no) VALUES
  ('baseline',    '人员基础属性', NULL,       600),
  ('demographic', '人口特征',     'baseline', 601);

-- ---------- 3. 扩展属性定义（租户级，可增减） ----------
CREATE TABLE tag_ext_attr_def (
  id            BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id     VARCHAR(64)  NOT NULL,
  attr_key      VARCHAR(64)  NOT NULL,
  attr_name     VARCHAR(128) NOT NULL,
  subject_type  VARCHAR(16)  NOT NULL,
  value_type    VARCHAR(16)  NOT NULL,
  options_json  TEXT,
  default_value VARCHAR(512),
  editable      TINYINT      NOT NULL,
  visible       TINYINT      NOT NULL,
  sort_no       INT          NOT NULL,
  status        VARCHAR(16)  NOT NULL,
  created_at    DATETIME     NOT NULL,
  updated_at    DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, attr_key)
);

CREATE TABLE tag_ext_attr (
  id           BIGINT       NOT NULL AUTO_INCREMENT,
  tenant_id    VARCHAR(64)  NOT NULL,
  subject_type VARCHAR(16)  NOT NULL,
  subject_code VARCHAR(64)  NOT NULL,
  attr_key     VARCHAR(64)  NOT NULL,
  attr_value   TEXT,
  updated_by   VARCHAR(64),
  created_at   DATETIME     NOT NULL,
  updated_at   DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (tenant_id, subject_type, subject_code, attr_key)
);
CREATE INDEX idx_tag_ext_attr_subject ON tag_ext_attr (tenant_id, subject_type, subject_code);

-- ---------- 4. 内置扩展属性（risk_control 租户） ----------
INSERT INTO tag_ext_attr_def
  (tenant_id, attr_key, attr_name, subject_type, value_type, options_json, default_value,
   editable, visible, sort_no, status, created_at, updated_at)
VALUES
  ('risk_control', 'show_screen',   '标签展示屏幕',     'TAG',  'enum', '["主","副"]', '副', 1, 1, 10, 'enabled', NOW(), NOW()),
  ('risk_control', 'hidden',        '是否隐藏',         'TAG',  'bool', NULL,          'false', 1, 1, 20, 'enabled', NOW(), NOW()),
  ('risk_control', 'script',        '标签话术',         'TAG',  'text', NULL,          NULL, 1, 1, 30, 'enabled', NOW(), NOW()),
  ('risk_control', 'risk_priority', '标签风控展示优先级', 'TAG',  'int',  NULL,          NULL, 1, 1, 40, 'enabled', NOW(), NOW()),
  ('risk_control', 'dim1_priority', '一级维度优先级',   'DIM1', 'int',  NULL,          NULL, 1, 1, 50, 'enabled', NOW(), NOW());
