-- ============================================================================
-- StarRocks 存量表升级：confidence 由 FLOAT 改为 VARCHAR(10)，取值「高 / 中 / 低」
--
-- 背景：
--   tag_value_latest / tag_value_history 的 confidence 原为 0~1 的数值（FLOAT），
--   现统一改为枚举字符串（高 / 中 / 低），列类型 VARCHAR(10)。
--
-- 为什么重建表：
--   StarRocks 3.2.x 对 FLOAT -> VARCHAR 的 MODIFY COLUMN 静默无效（返回成功但
--   列类型不变），且不支持 RENAME COLUMN；主键表也不允许修改主键列。因此沿用
--   v9 的「新建同构表 -> 复制数据（转换 confidence）-> 重命名切换」方案，
--   3.2.x / 4.x 均可执行。
--
-- 存量映射：NULL -> NULL；>= 0.8 -> 高；>= 0.5 -> 中；其余 -> 低
--
-- 执行方式（建议低峰期或暂停写入后执行）：
--   mysql -h<FE_HOST> -P9030 -u<user> -p tag_platform < migrate-v10-confidence.sql
--   或分两段执行：先执行「阶段一」并核对行数，再执行「阶段二」切换。
--
-- 注意：
--   * 阶段二的 RENAME 需要**表级** ALTER 权限。StarRocks 4.0 中授予 DATABASE 的
--     ALTER/DROP 不会向下覆盖到表，只给库级权限时会被拒绝（错误码 5203）；
--     先执行 deploy/starrocks/grant-app-user.sql，或直接用 root/管理员账号。
--   * tag_value_latest 由外部写入程序维护，切换后写入方必须同步改为传
--     「高 / 中 / 低」，否则写入失败或落成非法值。
--   * 阶段一复制完成到阶段二切换之间的新增数据会丢失，建议停写后执行；
--     latest 为 Primary Key 表，可重跑一次 INSERT 追赶（按主键覆盖）；
--     history 为 DUPLICATE 表，重跑会产生重复行，切勿重复执行。
--   * 每个环境只执行一次；阶段二要求 *_bak 表名未被占用。
--   * 阶段二确认数据无误后才会删除备份表，如需保留请注释最后两条 DROP。
-- ============================================================================

USE tag_platform;

-- ===========================================================================
-- 阶段一：创建新表并复制数据（不影响在线读写）
-- ===========================================================================

DROP TABLE IF EXISTS tag_value_latest_v10;
CREATE TABLE tag_value_latest_v10 (
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

DROP TABLE IF EXISTS tag_value_history_v10;
CREATE TABLE tag_value_history_v10 (
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
DUPLICATE KEY (tenant_id, object_type, object_id, tag_code, op_time)
COMMENT '标签变更流水（追加式，永久保留，审计与血缘追溯）'
DISTRIBUTED BY HASH(object_id) BUCKETS 16
PROPERTIES (
  "replication_num" = "1"
);

INSERT INTO tag_value_latest_v10 (
  tenant_id, object_type, object_id, tag_code, tag_value, value_type, status,
  biz_time, operate_time, expire_time, operator_id, operator_name, operator_type,
  operator_org, source, confidence, remark, evidence_url, audit_by, audit_time,
  audit_remark, request_id, op_time, ver, ext_json
)
SELECT
  tenant_id, object_type, object_id, tag_code, tag_value, value_type, status,
  biz_time, operate_time, expire_time, operator_id, operator_name, operator_type,
  operator_org, source,
  CASE
    WHEN confidence IS NULL THEN NULL
    WHEN confidence >= 0.8 THEN '高'
    WHEN confidence >= 0.5 THEN '中'
    ELSE '低'
  END,
  remark, evidence_url, audit_by, audit_time,
  audit_remark, request_id, op_time, ver, ext_json
FROM tag_value_latest;

INSERT INTO tag_value_history_v10 (
  tenant_id, object_type, object_id, tag_code, op_time, tag_value, value_type, status,
  biz_time, operate_time, expire_time, operator_id, operator_name, operator_type,
  operator_org, source, confidence, remark, evidence_url, audit_by, audit_time,
  audit_remark, request_id, ver, ext_json
)
SELECT
  tenant_id, object_type, object_id, tag_code, op_time, tag_value, value_type, status,
  biz_time, operate_time, expire_time, operator_id, operator_name, operator_type,
  operator_org, source,
  CASE
    WHEN confidence IS NULL THEN NULL
    WHEN confidence >= 0.8 THEN '高'
    WHEN confidence >= 0.5 THEN '中'
    ELSE '低'
  END,
  remark, evidence_url, audit_by, audit_time,
  audit_remark, request_id, ver, ext_json
FROM tag_value_history;

-- 核对：新表行数应与旧表一致；confidence 只应出现 高/中/低 与 NULL
SELECT 'tag_value_latest' AS table_name, COUNT(*) AS cnt FROM tag_value_latest
UNION ALL SELECT 'tag_value_latest_v10', COUNT(*) FROM tag_value_latest_v10
UNION ALL SELECT 'tag_value_history', COUNT(*) FROM tag_value_history
UNION ALL SELECT 'tag_value_history_v10', COUNT(*) FROM tag_value_history_v10;

SELECT confidence, COUNT(*) AS cnt FROM tag_value_latest_v10 GROUP BY confidence ORDER BY 1;
SELECT COUNT(*) AS illegal_rows FROM tag_value_latest_v10
WHERE confidence IS NOT NULL AND confidence NOT IN ('高', '中', '低');

-- ===========================================================================
-- 阶段二：切换表名并清理备份（确认阶段一数据无误、写入方已改为传枚举后执行）
-- ===========================================================================

USE tag_platform;

ALTER TABLE tag_value_latest RENAME tag_value_latest_bak;
ALTER TABLE tag_value_latest_v10 RENAME tag_value_latest;

ALTER TABLE tag_value_history RENAME tag_value_history_bak;
ALTER TABLE tag_value_history_v10 RENAME tag_value_history;

DROP TABLE tag_value_latest_bak;
DROP TABLE tag_value_history_bak;
