-- ============================================================================
-- StarRocks 存量表升级：对象标识口径对齐（注释）
--
-- 背景：建表注释长期写作「对象唯一标识（司机ID/车牌号/车队ID/事件ID）」，
--       会让读表的人误以为车辆的 object_id 是车牌号。现行口径：
--         object_id   = 车辆唯一 ID（如 VH10086）/ 司机 ID / 车队 ID / 事件 ID
--         object_name = 车牌号、姓名、车队名等展示名称（不参与主键/幂等/频控）
--
-- 用法：
--   mysql -h<FE_HOST> -P9030 -u<user> -p tag_platform < migrate-v11-object-id-comment.sql
--
-- 为什么 object_id 的列注释改不了：
--   object_id 是 PRIMARY KEY / DUPLICATE KEY 的 key 列，StarRocks 拒绝
--   `MODIFY COLUMN`（报 "Can not modify key column: object_id for primary key table"）。
--   因此对象标识口径写在**表注释**（SHOW CREATE TABLE 末尾可见），列注释只修非 key 列。
--   新建库由 db/starrocks/schema.sql 建表时直接写正确注释，不受此限制。
--
-- 注意：
--   1. 表注释 `ALTER TABLE ... COMMENT = '...'` 立即生效，不触发 schema change。
--   2. `MODIFY COLUMN object_name` 走轻量 schema change（异步，秒级）。若报
--      「Table ... is doing schema change」，等 SHOW ALTER TABLE COLUMN 里 FINISHED 后重跑。
--   3. 仅改注释，不改类型与约束；MODIFY 必须带完整定义，否则会丢 NULL 约束。
-- ============================================================================

USE tag_platform;

-- 1) 表注释：承载完整的对象标识口径（object_id 是 key 列，只能在表级说明）
ALTER TABLE tag_value_latest
  COMMENT = '标签最新值宽表（画像查询数据源，由外部写入程序维护）。对象标识：object_id=车辆唯一ID/司机ID/车队ID/事件ID，object_name=车牌号/姓名等展示名';

ALTER TABLE tag_value_history
  COMMENT = '标签变更流水（追加式，永久保留，审计与血缘追溯）。对象标识：object_id=车辆唯一ID/司机ID/车队ID/事件ID，object_name=车牌号/姓名等展示名';

-- 2) 非 key 列：object_name 的注释可直接改
ALTER TABLE tag_value_latest
  MODIFY COLUMN object_name VARCHAR(256) NULL
  COMMENT '对象名称（车辆=车牌号；司机姓名/车队名等，可选，仅展示不参与主键）';

ALTER TABLE tag_value_history
  MODIFY COLUMN object_name VARCHAR(256) NULL
  COMMENT '对象名称（车辆=车牌号；司机姓名/车队名等，可选，仅展示不参与主键）';
