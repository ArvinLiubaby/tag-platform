-- ============================================================================
-- 应用账号权限：tag_platform 库的 DDL + 读写
-- 适用：StarRocks 3.2+ / 4.0（在 4.0.7 上实测）
--
-- 背景（实测结论，StarRocks 4.0）：
--   * 授予 DATABASE 的 ALTER / DROP 不会向下覆盖到表，表级 ALTER / UPDATE / DELETE
--     / DROP 一律报 ERROR 5203 Access denied；
--   * 必须额外授予 ON ALL TABLES IN DATABASE，且该授权对之后新建的表同样生效（动态）；
--   * 应用启动只需要 SELECT / INSERT，其余权限仅供运维与迁移使用。
--
-- 用法：
--   mysql -h<fe_host> -P9030 -uroot -p < grant-app-user.sql
--   账号密码由部署环境注入，本文件不保存口令；若账号尚未创建，先执行 CREATE USER：
--     CREATE USER IF NOT EXISTS 'tag_user'@'%' IDENTIFIED BY '<password>';
-- ============================================================================

GRANT USAGE ON CATALOG default_catalog TO USER 'tag_user'@'%';

-- 库级：建表、建视图、建物化视图、建函数、建 Pipe
GRANT CREATE TABLE, DROP, ALTER, CREATE VIEW, CREATE FUNCTION, CREATE MATERIALIZED VIEW, CREATE PIPE
    ON DATABASE tag_platform TO USER 'tag_user'@'%';

-- 表级：读写 + 改表 + 删表（对存量表与后续新建表均生效）
GRANT SELECT, INSERT, UPDATE, DELETE, ALTER, DROP
    ON ALL TABLES IN DATABASE tag_platform TO USER 'tag_user'@'%';

-- 校验：应看到两条 GRANT ... ON DATABASE / ON ALL TABLES IN DATABASE
SHOW GRANTS FOR 'tag_user'@'%';
