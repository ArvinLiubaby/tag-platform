-- ============================================================================
-- StarRocks 存量表升级：补充表/字段注释 + tag_value_history 改为永久保留
--
-- 背景：
--   1. 旧版 tag_value_history 使用按月动态分区（start=-3），只保留约 3 个月；
--      新版改为无分区表，数据永久保留，不再自动淘汰历史分区。
--   2. 旧表字段 COMMENT 为空；StarRocks 主键表不允许直接 MODIFY 主键列，
--      因此采用「新建同构表 -> 复制数据 -> 重命名切换」的方式。
--
-- 执行方式（建议停机或低峰期执行）：
--   mysql -h<FE_HOST> -P9030 -uroot < migrate-v9-comments-retention.sql
--   或分两段执行：先执行「阶段一」并核对行数，再执行「阶段二」切换。
--
-- 注意：
--   * 本脚本每个环境只执行一次；阶段二要求 _bak 表名未被占用。
--   * 阶段二完成后确认数据无误，备份表已由脚本删除；如需保留备份请注释最后两条 DROP。
-- ============================================================================

USE tag_platform;

-- ===========================================================================
-- 阶段一：创建新表并复制数据（不影响在线读写）
-- ===========================================================================

DROP TABLE IF EXISTS tag_value_latest_v9;
CREATE TABLE tag_value_latest_v9 (
  tenant_id     VARCHAR(64)   NOT NULL COMMENT '租户标识',
  object_type   VARCHAR(32)   NOT NULL COMMENT '对象类型：driver/vehicle/fleet/alarm_event',
  object_id     VARCHAR(128)  NOT NULL COMMENT '对象唯一标识（司机ID/车牌号/车队ID/事件ID）',
  tag_code      VARCHAR(64)   NOT NULL COMMENT '标签编码',
  tag_value     VARCHAR(2048) NULL COMMENT '标签值（统一按字符串存储）',
  value_type    VARCHAR(16)   NULL COMMENT '值类型：boolean/number/enum/string/date',
  status        VARCHAR(16)   NOT NULL COMMENT '值状态：effective-生效、pending_audit-待审核、revoked-已作废、expired-已过期',
  biz_time      DATETIME      NULL COMMENT '业务发生时间',
  operate_time  DATETIME      NULL COMMENT '业务操作时间',
  expire_time   DATETIME      NULL COMMENT '值过期时间（空表示不过期）',
  operator_id   VARCHAR(64)   NULL COMMENT '操作人标识',
  operator_name VARCHAR(128)  NULL COMMENT '操作人名称',
  operator_type VARCHAR(16)   NULL COMMENT '操作人类型：human-人工、system-系统、algo-算法',
  operator_org  VARCHAR(128)  NULL COMMENT '操作人所属组织',
  source        VARCHAR(64)   NULL COMMENT '数据来源（open_api/console/import 等）',
  confidence    FLOAT         NULL COMMENT '置信度（0-1，算法标签使用）',
  remark        VARCHAR(2048) NULL COMMENT '备注/说明（敏感标签写入时必填）',
  evidence_url  VARCHAR(512)  NULL COMMENT '证据链接（图片/工单等）',
  audit_by      VARCHAR(64)   NULL COMMENT '审核人（历史字段，平台值审核已停用）',
  audit_time    DATETIME      NULL COMMENT '审核时间（历史字段，平台值审核已停用）',
  audit_remark  VARCHAR(1024) NULL COMMENT '审核意见（历史字段，平台值审核已停用）',
  request_id    VARCHAR(64)   NULL COMMENT '请求追踪 ID',
  op_time       DATETIME      NOT NULL COMMENT '记录写入时间（同对象+标签按此倒序取最新）',
  ver           BIGINT        NULL COMMENT '版本号（每次变更递增）',
  ext_json      VARCHAR(2048) NULL COMMENT '扩展字段（JSON）'
) ENGINE=OLAP
PRIMARY KEY (tenant_id, object_type, object_id, tag_code)
COMMENT '标签最新值宽表（画像查询数据源，由外部写入程序维护）'
DISTRIBUTED BY HASH(object_id) BUCKETS 16
PROPERTIES (
  "replication_num" = "1",
  "enable_persistent_index" = "true"
);

INSERT INTO tag_value_latest_v9 SELECT * FROM tag_value_latest;

DROP TABLE IF EXISTS tag_value_history_v9;
CREATE TABLE tag_value_history_v9 (
  tenant_id     VARCHAR(64)   NOT NULL COMMENT '租户标识',
  object_type   VARCHAR(32)   NOT NULL COMMENT '对象类型：driver/vehicle/fleet/alarm_event',
  object_id     VARCHAR(128)  NOT NULL COMMENT '对象唯一标识（司机ID/车牌号/车队ID/事件ID）',
  tag_code      VARCHAR(64)   NOT NULL COMMENT '标签编码',
  op_time       DATETIME      NOT NULL COMMENT '记录写入时间（同对象+标签按此倒序取最新）',
  tag_value     VARCHAR(2048) NULL COMMENT '标签值（统一按字符串存储）',
  value_type    VARCHAR(16)   NULL COMMENT '值类型：boolean/number/enum/string/date',
  status        VARCHAR(16)   NOT NULL COMMENT '值状态：effective-生效、pending_audit-待审核、revoked-已作废、expired-已过期',
  biz_time      DATETIME      NULL COMMENT '业务发生时间',
  operate_time  DATETIME      NULL COMMENT '业务操作时间',
  expire_time   DATETIME      NULL COMMENT '值过期时间（空表示不过期）',
  operator_id   VARCHAR(64)   NULL COMMENT '操作人标识',
  operator_name VARCHAR(128)  NULL COMMENT '操作人名称',
  operator_type VARCHAR(16)   NULL COMMENT '操作人类型：human-人工、system-系统、algo-算法',
  operator_org  VARCHAR(128)  NULL COMMENT '操作人所属组织',
  source        VARCHAR(64)   NULL COMMENT '数据来源（open_api/console/import 等）',
  confidence    FLOAT         NULL COMMENT '置信度（0-1，算法标签使用）',
  remark        VARCHAR(2048) NULL COMMENT '备注/说明（敏感标签写入时必填）',
  evidence_url  VARCHAR(512)  NULL COMMENT '证据链接（图片/工单等）',
  audit_by      VARCHAR(64)   NULL COMMENT '审核人（历史字段，平台值审核已停用）',
  audit_time    DATETIME      NULL COMMENT '审核时间（历史字段，平台值审核已停用）',
  audit_remark  VARCHAR(1024) NULL COMMENT '审核意见（历史字段，平台值审核已停用）',
  request_id    VARCHAR(64)   NULL COMMENT '请求追踪 ID',
  ver           BIGINT        NULL COMMENT '版本号（每次变更递增）',
  ext_json      VARCHAR(2048) NULL COMMENT '扩展字段（JSON）'
) ENGINE=OLAP
DUPLICATE KEY(tenant_id, object_type, object_id, tag_code, op_time)
COMMENT '标签变更流水（追加式，永久保留，审计与血缘追溯）'
DISTRIBUTED BY HASH(object_id) BUCKETS 16
PROPERTIES (
  "replication_num" = "1"
);

INSERT INTO tag_value_history_v9 SELECT * FROM tag_value_history;

-- 核对行数：新表应与旧表一致（写入仍在进行时，旧表可能略多，可在停写后重跑复制）
SELECT 'tag_value_latest' AS table_name, COUNT(*) AS old_rows FROM tag_value_latest
UNION ALL SELECT 'tag_value_latest_v9', COUNT(*) FROM tag_value_latest_v9
UNION ALL SELECT 'tag_value_history', COUNT(*) FROM tag_value_history
UNION ALL SELECT 'tag_value_history_v9', COUNT(*) FROM tag_value_history_v9;

-- ===========================================================================
-- 阶段二：切换表名并清理备份（确认阶段一行数一致后执行）
-- ===========================================================================

ALTER TABLE tag_value_latest RENAME tag_value_latest_bak;
ALTER TABLE tag_value_latest_v9 RENAME tag_value_latest;

ALTER TABLE tag_value_history RENAME tag_value_history_bak;
ALTER TABLE tag_value_history_v9 RENAME tag_value_history;

DROP TABLE tag_value_latest_bak;
DROP TABLE tag_value_history_bak;
