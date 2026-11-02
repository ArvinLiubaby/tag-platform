-- ============================================================================
-- StarRocks 初始化脚本（生产环境）：标签最新值宽表 + 变更流水
-- 应用启动时会自动执行同内容 DDL（CREATE TABLE IF NOT EXISTS），本脚本供人工初 始化使用：
--   mysql -h<FE_HOST> -P9030 -uroot < init.sql
-- 存量表升级（补注释、流水改永久保留）见 deploy/starrocks/migrate-v9-comments-retention.sql
-- ============================================================================

CREATE DATABASE IF NOT EXISTS tag_platform;

USE tag_platform;

-- 标签最新值宽表：Primary Key 模型，upsert 收敛（last-write-wins），画像点查数 据源
-- 写入方：外部写入程序（应用只追加 tag_value_history，不再直接写本表）
CREATE TABLE IF NOT EXISTS tag_value_latest (
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
  confidence    VARCHAR(10)   NULL COMMENT '置信度：高/中/低（算法标签使用）',
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

-- 标签变更流水：追加式、永久保留（不设置分区淘汰策略），审计与血缘追溯
-- 注意：StarRocks 要求 key 列位于 schema 最前且顺序一致，op_time 参与 DUPLICATE KEY 须紧随 tag_code
CREATE TABLE IF NOT EXISTS tag_value_history (
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
  confidence    VARCHAR(10)   NULL COMMENT '置信度：高/中/低（算法标签使用）',
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
