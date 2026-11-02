-- ============================================================================
-- V9007 打标工作台与任务管理（MySQL 8 / OceanBase MySQL 模式 / H2 MySQL 模式）
-- 1. console_user 增加租户 / 操作员 / 班组字段
-- 2. 新增任务、任务标签、任务项、提议值、流转事件表
-- ============================================================================

ALTER TABLE console_user ADD COLUMN tenant_id VARCHAR(64);
ALTER TABLE console_user ADD COLUMN operator_id VARCHAR(64);
ALTER TABLE console_user ADD COLUMN operator_name VARCHAR(128);
ALTER TABLE console_user ADD COLUMN operator_org VARCHAR(128);
ALTER TABLE console_user ADD COLUMN team VARCHAR(64);

UPDATE console_user
   SET tenant_id = 'risk_control',
       operator_id = username,
       operator_name = display_name,
       operator_org = '平台',
       team = '平台'
 WHERE user_role <> 'ADMIN' AND operator_id IS NULL;

UPDATE console_user
   SET operator_id = 'admin',
       operator_name = '平台管理员',
       operator_org = '平台'
 WHERE username = 'admin' AND operator_id IS NULL;

CREATE TABLE tag_task (
  id                BIGINT       NOT NULL AUTO_INCREMENT,
  task_code         VARCHAR(32)  NOT NULL,
  tenant_id         VARCHAR(64)  NOT NULL,
  title             VARCHAR(128) NOT NULL,
  description       VARCHAR(1024),
  object_type       VARCHAR(32)  NOT NULL,
  task_type         VARCHAR(16)  NOT NULL,
  status            VARCHAR(16)  NOT NULL,
  priority          VARCHAR(8)   NOT NULL,
  assignment_mode   VARCHAR(16)  NOT NULL,
  assignee_user_id  BIGINT,
  team              VARCHAR(64),
  reviewer_user_id  BIGINT,
  review_required   TINYINT      NOT NULL,
  total_items       INT          NOT NULL,
  completed_items   INT          NOT NULL,
  rejected_items    INT          NOT NULL,
  due_at            DATETIME,
  published_at      DATETIME,
  completed_at      DATETIME,
  created_by        VARCHAR(64)  NOT NULL,
  created_at        DATETIME     NOT NULL,
  updated_at        DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (task_code)
);
CREATE INDEX idx_tag_task_tenant_status ON tag_task (tenant_id, status);
CREATE INDEX idx_tag_task_assignee ON tag_task (assignee_user_id, status);
CREATE INDEX idx_tag_task_due ON tag_task (due_at);

CREATE TABLE tag_task_tag (
  task_id   BIGINT      NOT NULL,
  tag_code  VARCHAR(64) NOT NULL,
  required  TINYINT     NOT NULL,
  sort_no   INT         NOT NULL,
  PRIMARY KEY (task_id, tag_code)
);

CREATE TABLE tag_task_item (
  id               BIGINT       NOT NULL AUTO_INCREMENT,
  task_id          BIGINT       NOT NULL,
  object_type      VARCHAR(32)  NOT NULL,
  object_id        VARCHAR(128) NOT NULL,
  status           VARCHAR(16)  NOT NULL,
  assignee_user_id BIGINT,
  operator_id      VARCHAR(64),
  operator_name    VARCHAR(128),
  operator_org     VARCHAR(128),
  claimed_at       DATETIME,
  submitted_at     DATETIME,
  reviewed_at      DATETIME,
  reviewer_user_id BIGINT,
  review_remark    VARCHAR(512),
  created_at       DATETIME     NOT NULL,
  updated_at       DATETIME     NOT NULL,
  PRIMARY KEY (id),
  UNIQUE (task_id, object_type, object_id)
);
CREATE INDEX idx_tag_task_item_task_status ON tag_task_item (task_id, status);
CREATE INDEX idx_tag_task_item_assignee ON tag_task_item (assignee_user_id, status);

CREATE TABLE tag_task_item_value (
  item_id      BIGINT        NOT NULL,
  tag_code     VARCHAR(64)   NOT NULL,
  tag_value    VARCHAR(2048),
  remark       VARCHAR(2048),
  evidence_url VARCHAR(512),
  confidence   VARCHAR(10),
  status       VARCHAR(16)   NOT NULL,
  created_at   DATETIME      NOT NULL,
  updated_at   DATETIME      NOT NULL,
  PRIMARY KEY (item_id, tag_code)
);

CREATE TABLE tag_task_event (
  id          BIGINT       NOT NULL AUTO_INCREMENT,
  task_id     BIGINT       NOT NULL,
  item_id     BIGINT,
  event_type  VARCHAR(32)  NOT NULL,
  from_status VARCHAR(16),
  to_status   VARCHAR(16),
  operator    VARCHAR(128),
  remark      VARCHAR(512),
  created_at  DATETIME     NOT NULL,
  PRIMARY KEY (id)
);
CREATE INDEX idx_tag_task_event_task ON tag_task_event (task_id, created_at);
