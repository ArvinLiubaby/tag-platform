-- 一次性手工操作：把 tag_value_history 的打标结果折叠成 tag_value_latest
--
-- 背景：平台只追加 history + outbox，latest 由外部同步程序维护。测试/演示环境没有那个程序时，
--       控制台写完标签、画像页却读不到值。此脚本按「同对象+同标签取最后一条」的规则把 history
--       折算进 latest（PRIMARY KEY 表，INSERT 即 upsert）。
--
-- 用法（手动执行，不要配成定时任务）：
--   docker exec -i tp-test-starrocks mysql -h127.0.0.1 -P9030 -uroot < deploy/test/backfill-latest.sql
--   生产同理，换成生产 StarRocks 连接即可。
--
-- 口径：
--   * 排序 op_time DESC, ver DESC  —— 与画像查询「取最新」一致
--   * 只写折叠后的最后一条，revoked / expired 原样带过去（查询侧会自行过滤）
--   * 只增改不删除：history 里没有的 latest 行保持原样

INSERT INTO tag_platform.tag_value_latest
(tenant_id, object_type, object_id, tag_code, tag_value, value_type, status, biz_time, operate_time, expire_time,
 operator_id, operator_name, operator_type, operator_org, source, confidence, remark, evidence_url,
 audit_by, audit_time, audit_remark, request_id, op_time, ver, ext_json, object_name)
SELECT tenant_id, object_type, object_id, tag_code, tag_value, value_type, status, biz_time, operate_time, expire_time,
       operator_id, operator_name, operator_type, operator_org, source, confidence, remark, evidence_url,
       audit_by, audit_time, audit_remark, request_id, op_time, ver, ext_json, object_name
FROM (
  SELECT h.*,
         ROW_NUMBER() OVER (PARTITION BY tenant_id, object_type, object_id, tag_code
                            ORDER BY op_time DESC, ver DESC) rn
  FROM tag_platform.tag_value_history h
) t
WHERE rn = 1;

-- 只补某一个对象时，把上面的 WHERE rn = 1 改成：
--   WHERE rn = 1 AND object_type = 'driver' AND object_id = 'D1086694'
