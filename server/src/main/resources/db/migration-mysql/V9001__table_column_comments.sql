-- ============================================================================
-- V9001 元数据与治理库：表级 / 字段级注释（MySQL 8 / OceanBase MySQL 模式）
-- 说明：
--   1. 本脚本位于 db/migration-mysql，仅生产（MySQL）Flyway 执行；H2 demo 使用
--      db/migration-h2/V1002__table_column_comments.sql（COMMENT ON 语法）。
--   2. 已应用的 V1-V8 不可修改（Flyway checksum），注释统一在本脚本补齐。
--   3. MODIFY COLUMN 必须完整保留原列类型与约束，后续调整列定义时同步维护注释。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 租户
-- ---------------------------------------------------------------------------
ALTER TABLE tenant
  COMMENT = '租户（接入的业务系统/平台来源）',
  MODIFY COLUMN id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tenant_id     VARCHAR(64)  NOT NULL COMMENT '租户唯一标识（接入系统编码）',
  MODIFY COLUMN tenant_name   VARCHAR(128) NOT NULL COMMENT '租户名称',
  MODIFY COLUMN access_system VARCHAR(128) NULL COMMENT '接入系统名称/描述',
  MODIFY COLUMN status        VARCHAR(16)  NOT NULL COMMENT '租户状态：enabled-启用，disabled-停用',
  MODIFY COLUMN write_qps     INT          NOT NULL COMMENT '写入 QPS 配额上限',
  MODIFY COLUMN read_qps      INT          NOT NULL COMMENT '查询 QPS 配额上限',
  MODIFY COLUMN created_at    DATETIME     NOT NULL COMMENT '创建时间',
  MODIFY COLUMN updated_at    DATETIME     NOT NULL COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- 应用凭证
-- ---------------------------------------------------------------------------
ALTER TABLE app_credential
  COMMENT = '应用凭证（开放接口 AppKey/Secret）',
  MODIFY COLUMN id         BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tenant_id  VARCHAR(64)  NOT NULL COMMENT '所属租户标识',
  MODIFY COLUMN app_key    VARCHAR(64)  NOT NULL COMMENT '应用标识（开放接口 AppKey，全局唯一）',
  MODIFY COLUMN secret_enc VARCHAR(512) NOT NULL COMMENT '加密后的应用密钥（enc:v1:<base64>）',
  MODIFY COLUMN status     VARCHAR(16)  NOT NULL COMMENT '凭证状态：enabled-启用，disabled-停用',
  MODIFY COLUMN created_at DATETIME     NOT NULL COMMENT '创建时间';

-- ---------------------------------------------------------------------------
-- 标签类目
-- ---------------------------------------------------------------------------
ALTER TABLE tag_category
  COMMENT = '标签类目（多级树，用于标签分组与展示）',
  MODIFY COLUMN id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN category_code VARCHAR(64)  NOT NULL COMMENT '类目编码（全局唯一）',
  MODIFY COLUMN category_name VARCHAR(128) NOT NULL COMMENT '类目名称',
  MODIFY COLUMN parent_code   VARCHAR(64)  NULL COMMENT '父类目编码，顶级类目为空',
  MODIFY COLUMN sort_no       INT          NOT NULL COMMENT '同级排序号（升序）';

-- ---------------------------------------------------------------------------
-- 标签字典
-- ---------------------------------------------------------------------------
ALTER TABLE tag_meta
  COMMENT = '标签字典（标签定义、值类型、敏感级别与写入开关）',
  MODIFY COLUMN id               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tag_code         VARCHAR(64)  NOT NULL COMMENT '标签编码（全局唯一，小写字母/数字/下划线）',
  MODIFY COLUMN tag_name         VARCHAR(128) NOT NULL COMMENT '标签名称',
  MODIFY COLUMN category_code    VARCHAR(64)  NULL COMMENT '所属类目编码（关联 tag_category.category_code）',
  MODIFY COLUMN value_type       VARCHAR(16)  NOT NULL COMMENT '值类型：boolean/number/enum/string/date',
  MODIFY COLUMN value_range      TEXT         NULL COMMENT '取值范围或枚举定义（JSON/文本，可为空）',
  MODIFY COLUMN tag_type         VARCHAR(16)  NOT NULL COMMENT '标签类型：fact-事实、rule-规则、algo-算法',
  MODIFY COLUMN need_value_audit TINYINT      NOT NULL COMMENT '是否需要值审核：1-需要（流水记 pending_audit），0-不需要；平台值审核已停用',
  MODIFY COLUMN value_ttl_days   INT          NOT NULL COMMENT '标签值有效期（天），0 表示不过期',
  MODIFY COLUMN tenant_id        VARCHAR(64)  NOT NULL COMMENT '所属租户标识；PUBLIC 表示平台公共标签',
  MODIFY COLUMN sensitivity      VARCHAR(8)   NOT NULL COMMENT '数据敏感级别：L1-低、L2-中、L3-高',
  MODIFY COLUMN status           VARCHAR(16)  NOT NULL COMMENT '标签状态：reviewing-待审核、online-已上线、offline-已下线',
  MODIFY COLUMN version          INT          NOT NULL COMMENT '元数据版本号（每次变更递增）',
  MODIFY COLUMN created_at       DATETIME     NOT NULL COMMENT '创建时间',
  MODIFY COLUMN updated_at       DATETIME     NOT NULL COMMENT '更新时间',
  MODIFY COLUMN object_type      VARCHAR(64)  NULL COMMENT '绑定对象类型：driver/vehicle/fleet/alarm_event，* 表示通用',
  MODIFY COLUMN source_mode      VARCHAR(16)  NULL COMMENT '打标来源：manual-手工、statistics-统计、hybrid-混合、algo-算法',
  MODIFY COLUMN writable         TINYINT      NOT NULL DEFAULT 1 COMMENT '是否允许接口写入：1-允许，0-只读';

-- ---------------------------------------------------------------------------
-- 标签定义审核流水
-- ---------------------------------------------------------------------------
ALTER TABLE tag_audit
  COMMENT = '标签定义审核流水（注册/变更审批留痕）',
  MODIFY COLUMN id           BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tag_code     VARCHAR(64)  NOT NULL COMMENT '标签编码',
  MODIFY COLUMN action_type  VARCHAR(32)  NOT NULL COMMENT '审核动作：register-注册、update-变更（预留）',
  MODIFY COLUMN submit_by    VARCHAR(64)  NOT NULL COMMENT '提交人（控制台用户名）',
  MODIFY COLUMN audit_by     VARCHAR(64)  NULL COMMENT '审核人（控制台用户名）',
  MODIFY COLUMN audit_remark VARCHAR(512) NULL COMMENT '审核意见（通过时必填）',
  MODIFY COLUMN status       VARCHAR(16)  NOT NULL COMMENT '审核状态：reviewing-待审核、approved-通过、rejected-驳回',
  MODIFY COLUMN created_at   DATETIME     NOT NULL COMMENT '提交时间';

-- ---------------------------------------------------------------------------
-- 对象类型注册表
-- ---------------------------------------------------------------------------
ALTER TABLE object_type_meta
  COMMENT = '对象类型注册表（driver/vehicle/fleet/alarm_event，可扩展）',
  MODIFY COLUMN object_type  VARCHAR(32)  NOT NULL COMMENT '对象类型编码（driver/vehicle/fleet/alarm_event）',
  MODIFY COLUMN type_name    VARCHAR(64)  NOT NULL COMMENT '对象类型名称',
  MODIFY COLUMN id_field     VARCHAR(64)  NOT NULL COMMENT '对象唯一标识字段名（如 driver_id、vehicle_plate）',
  MODIFY COLUMN id_pattern   VARCHAR(256) NULL COMMENT '对象标识正则校验规则',
  MODIFY COLUMN object_class VARCHAR(16)  NOT NULL COMMENT '对象类别：entity-实体、event-事件',
  MODIFY COLUMN default_ttl  INT          NOT NULL COMMENT '默认有效期（天），0 表示不过期',
  MODIFY COLUMN status       VARCHAR(16)  NOT NULL COMMENT '状态：enabled-启用，disabled-停用';

-- ---------------------------------------------------------------------------
-- 对象索引
-- ---------------------------------------------------------------------------
ALTER TABLE object_index
  COMMENT = '对象索引（打标时自动登记，记录对象首次/最近出现时间）',
  MODIFY COLUMN id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tenant_id   VARCHAR(64)  NOT NULL COMMENT '租户标识',
  MODIFY COLUMN object_type VARCHAR(32)  NOT NULL COMMENT '对象类型编码',
  MODIFY COLUMN object_id   VARCHAR(128) NOT NULL COMMENT '对象唯一标识',
  MODIFY COLUMN first_seen  DATETIME     NOT NULL COMMENT '首次出现时间（首次打标）',
  MODIFY COLUMN last_seen   DATETIME     NOT NULL COMMENT '最近出现时间（最近打标）';

-- ---------------------------------------------------------------------------
-- 幂等登记
-- ---------------------------------------------------------------------------
ALTER TABLE biz_request_id
  COMMENT = '写入幂等登记（biz_request_id 去重，重复提交返回首次结果）',
  MODIFY COLUMN id             BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN biz_request_id VARCHAR(96) NOT NULL COMMENT '业务请求幂等号（调用方生成，全局唯一）',
  MODIFY COLUMN tenant_id      VARCHAR(64) NOT NULL COMMENT '租户标识',
  MODIFY COLUMN status         VARCHAR(16) NOT NULL COMMENT '处理状态：processing-处理中、done-已完成',
  MODIFY COLUMN response_json  TEXT        NULL COMMENT '首次成功处理的响应报文（重复请求原样返回）',
  MODIFY COLUMN created_at     DATETIME    NOT NULL COMMENT '首次受理时间',
  MODIFY COLUMN updated_at     DATETIME    NOT NULL COMMENT '最近更新时间';

-- ---------------------------------------------------------------------------
-- Outbox 事件
-- ---------------------------------------------------------------------------
ALTER TABLE outbox_event
  COMMENT = 'Outbox 事件表（与写入同事务落库，供外部写入程序/下游消费）',
  MODIFY COLUMN id           BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN event_id     VARCHAR(64) NOT NULL COMMENT '事件唯一标识（UUID）',
  MODIFY COLUMN event_type   VARCHAR(64) NOT NULL COMMENT '事件类型：TAG_VALUE_WRITTEN/TAG_VALUE_PENDING_AUDIT/TAG_VALUE_REVOKED',
  MODIFY COLUMN tenant_id    VARCHAR(64) NOT NULL COMMENT '租户标识',
  MODIFY COLUMN payload      TEXT        NOT NULL COMMENT '事件报文（JSON）',
  MODIFY COLUMN status       VARCHAR(16) NOT NULL COMMENT '投递状态：pending-待投递（当前仅落库，未内置投递器）',
  MODIFY COLUMN retry_count  INT         NOT NULL COMMENT '投递重试次数',
  MODIFY COLUMN created_at   DATETIME    NOT NULL COMMENT '事件产生时间',
  MODIFY COLUMN delivered_at DATETIME    NULL COMMENT '投递成功时间';

-- ---------------------------------------------------------------------------
-- 审计日志
-- ---------------------------------------------------------------------------
ALTER TABLE audit_log
  COMMENT = '审计日志（控制台/开放接口操作留痕，保留 3 年）',
  MODIFY COLUMN id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN request_id  VARCHAR(64)  NULL COMMENT '请求追踪 ID',
  MODIFY COLUMN actor       VARCHAR(128) NOT NULL COMMENT '操作人标识（控制台用户或开放接口 AppKey）',
  MODIFY COLUMN actor_role  VARCHAR(32)  NULL COMMENT '操作人角色：ADMIN/AUDITOR/VIEWER/SYSTEM/OPEN_API',
  MODIFY COLUMN action_type VARCHAR(64)  NOT NULL COMMENT '操作类型（如 TAG_VALUE_WRITE、TAG_REGISTER、TENANT_CREATE）',
  MODIFY COLUMN tenant_id   VARCHAR(64)  NULL COMMENT '租户标识',
  MODIFY COLUMN object_type VARCHAR(32)  NULL COMMENT '对象类型编码',
  MODIFY COLUMN object_id   VARCHAR(128) NULL COMMENT '对象唯一标识',
  MODIFY COLUMN tag_code    VARCHAR(64)  NULL COMMENT '标签编码',
  MODIFY COLUMN detail      TEXT         NULL COMMENT '操作详情（JSON/文本）',
  MODIFY COLUMN result      VARCHAR(32)  NULL COMMENT '操作结果：success-成功、failed-失败',
  MODIFY COLUMN source_ip   VARCHAR(64)  NULL COMMENT '来源 IP',
  MODIFY COLUMN created_at  DATETIME     NOT NULL COMMENT '操作时间';

-- ---------------------------------------------------------------------------
-- 配额登记
-- ---------------------------------------------------------------------------
ALTER TABLE quota
  COMMENT = '配额登记（MVP 演示读表，正式限流走 Redis 计数器）',
  MODIFY COLUMN id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN tenant_id   VARCHAR(64) NOT NULL COMMENT '租户标识',
  MODIFY COLUMN quota_dim   VARCHAR(32) NOT NULL COMMENT '配额维度（如 write_qps、read_qps）',
  MODIFY COLUMN limit_value BIGINT      NOT NULL COMMENT '配额上限',
  MODIFY COLUMN used_value  BIGINT      NOT NULL COMMENT '已使用量',
  MODIFY COLUMN updated_at  DATETIME    NOT NULL COMMENT '更新时间';

-- ---------------------------------------------------------------------------
-- 控制台用户
-- ---------------------------------------------------------------------------
ALTER TABLE console_user
  COMMENT = '控制台用户（轻量 RBAC：ADMIN/AUDITOR/VIEWER）',
  MODIFY COLUMN id            BIGINT       NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  MODIFY COLUMN username      VARCHAR(64)  NOT NULL COMMENT '登录用户名（唯一）',
  MODIFY COLUMN password_hash VARCHAR(256) NOT NULL COMMENT '密码散列（pbkdf2:{iterations}:{salt}:{hash}；兼容种子数据 plain:{明文}）',
  MODIFY COLUMN display_name  VARCHAR(128) NOT NULL COMMENT '显示名称',
  MODIFY COLUMN user_role     VARCHAR(16)  NOT NULL COMMENT '角色：ADMIN-管理员、AUDITOR-审核员、VIEWER-只读',
  MODIFY COLUMN status        VARCHAR(16)  NOT NULL COMMENT '状态：enabled-启用，disabled-停用',
  MODIFY COLUMN created_at    DATETIME     NOT NULL COMMENT '创建时间';
