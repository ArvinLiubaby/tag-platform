-- ============================================================================
-- V1002 demo/H2 专用迁移：表级 / 字段级注释
-- 生产 MySQL 使用 db/migration-mysql/V9001__table_column_comments.sql；
-- 本脚本仅 demo（H2）执行，使用标准 COMMENT ON 语法。
-- 已应用的 V1001 不可修改（Flyway checksum），标签值表注释统一在此补齐。
-- ============================================================================

-- ---------------------------------------------------------------------------
-- 租户
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tenant IS '租户（接入的业务系统/平台来源）';
COMMENT ON COLUMN tenant.id IS '自增主键';
COMMENT ON COLUMN tenant.tenant_id IS '租户唯一标识（接入系统编码）';
COMMENT ON COLUMN tenant.tenant_name IS '租户名称';
COMMENT ON COLUMN tenant.access_system IS '接入系统名称/描述';
COMMENT ON COLUMN tenant.status IS '租户状态：enabled-启用，disabled-停用';
COMMENT ON COLUMN tenant.write_qps IS '写入 QPS 配额上限';
COMMENT ON COLUMN tenant.read_qps IS '查询 QPS 配额上限';
COMMENT ON COLUMN tenant.created_at IS '创建时间';
COMMENT ON COLUMN tenant.updated_at IS '更新时间';

-- ---------------------------------------------------------------------------
-- 应用凭证
-- ---------------------------------------------------------------------------
COMMENT ON TABLE app_credential IS '应用凭证（开放接口 AppKey/Secret）';
COMMENT ON COLUMN app_credential.id IS '自增主键';
COMMENT ON COLUMN app_credential.tenant_id IS '所属租户标识';
COMMENT ON COLUMN app_credential.app_key IS '应用标识（开放接口 AppKey，全局唯一）';
COMMENT ON COLUMN app_credential.secret_enc IS '加密后的应用密钥（enc:v1:<base64>）';
COMMENT ON COLUMN app_credential.status IS '凭证状态：enabled-启用，disabled-停用';
COMMENT ON COLUMN app_credential.created_at IS '创建时间';

-- ---------------------------------------------------------------------------
-- 标签类目
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tag_category IS '标签类目（多级树，用于标签分组与展示）';
COMMENT ON COLUMN tag_category.id IS '自增主键';
COMMENT ON COLUMN tag_category.category_code IS '类目编码（全局唯一）';
COMMENT ON COLUMN tag_category.category_name IS '类目名称';
COMMENT ON COLUMN tag_category.parent_code IS '父类目编码，顶级类目为空';
COMMENT ON COLUMN tag_category.sort_no IS '同级排序号（升序）';

-- ---------------------------------------------------------------------------
-- 标签字典
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tag_meta IS '标签字典（标签定义、值类型、敏感级别与写入开关）';
COMMENT ON COLUMN tag_meta.id IS '自增主键';
COMMENT ON COLUMN tag_meta.tag_code IS '标签编码（全局唯一，小写字母/数字/下划线）';
COMMENT ON COLUMN tag_meta.tag_name IS '标签名称';
COMMENT ON COLUMN tag_meta.category_code IS '所属类目编码（关联 tag_category.category_code）';
COMMENT ON COLUMN tag_meta.value_type IS '值类型：boolean/number/enum/string/date';
COMMENT ON COLUMN tag_meta.value_range IS '取值范围或枚举定义（JSON/文本，可为空）';
COMMENT ON COLUMN tag_meta.tag_type IS '标签类型：fact-事实、rule-规则、algo-算法';
COMMENT ON COLUMN tag_meta.need_value_audit IS '是否需要值审核：1-需要（流水记 pending_audit），0-不需要；平台值审核已停用';
COMMENT ON COLUMN tag_meta.value_ttl_days IS '标签值有效期（天），0 表示不过期';
COMMENT ON COLUMN tag_meta.tenant_id IS '所属租户标识；PUBLIC 表示平台公共标签';
COMMENT ON COLUMN tag_meta.sensitivity IS '数据敏感级别：L1-低、L2-中、L3-高';
COMMENT ON COLUMN tag_meta.status IS '标签状态：reviewing-待审核、online-已上线、offline-已下线';
COMMENT ON COLUMN tag_meta.version IS '元数据版本号（每次变更递增）';
COMMENT ON COLUMN tag_meta.created_at IS '创建时间';
COMMENT ON COLUMN tag_meta.updated_at IS '更新时间';
COMMENT ON COLUMN tag_meta.object_type IS '绑定对象类型：driver/vehicle/fleet/alarm_event，* 表示通用';
COMMENT ON COLUMN tag_meta.source_mode IS '打标来源：manual-手工、statistics-统计、hybrid-混合、algo-算法';
COMMENT ON COLUMN tag_meta.writable IS '是否允许接口写入：1-允许，0-只读';

-- ---------------------------------------------------------------------------
-- 标签定义审核流水
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tag_audit IS '标签定义审核流水（注册/变更审批留痕）';
COMMENT ON COLUMN tag_audit.id IS '自增主键';
COMMENT ON COLUMN tag_audit.tag_code IS '标签编码';
COMMENT ON COLUMN tag_audit.action_type IS '审核动作：register-注册、update-变更（预留）';
COMMENT ON COLUMN tag_audit.submit_by IS '提交人（控制台用户名）';
COMMENT ON COLUMN tag_audit.audit_by IS '审核人（控制台用户名）';
COMMENT ON COLUMN tag_audit.audit_remark IS '审核意见（通过时必填）';
COMMENT ON COLUMN tag_audit.status IS '审核状态：reviewing-待审核、approved-通过、rejected-驳回';
COMMENT ON COLUMN tag_audit.created_at IS '提交时间';

-- ---------------------------------------------------------------------------
-- 对象类型注册表
-- ---------------------------------------------------------------------------
COMMENT ON TABLE object_type_meta IS '对象类型注册表（driver/vehicle/fleet/alarm_event，可扩展）';
COMMENT ON COLUMN object_type_meta.object_type IS '对象类型编码（driver/vehicle/fleet/alarm_event）';
COMMENT ON COLUMN object_type_meta.type_name IS '对象类型名称';
COMMENT ON COLUMN object_type_meta.id_field IS '对象唯一标识字段名（如 driver_id、vehicle_plate）';
COMMENT ON COLUMN object_type_meta.id_pattern IS '对象标识正则校验规则';
COMMENT ON COLUMN object_type_meta.object_class IS '对象类别：entity-实体、event-事件';
COMMENT ON COLUMN object_type_meta.default_ttl IS '默认有效期（天），0 表示不过期';
COMMENT ON COLUMN object_type_meta.status IS '状态：enabled-启用，disabled-停用';

-- ---------------------------------------------------------------------------
-- 对象索引
-- ---------------------------------------------------------------------------
COMMENT ON TABLE object_index IS '对象索引（打标时自动登记，记录对象首次/最近出现时间）';
COMMENT ON COLUMN object_index.id IS '自增主键';
COMMENT ON COLUMN object_index.tenant_id IS '租户标识';
COMMENT ON COLUMN object_index.object_type IS '对象类型编码';
COMMENT ON COLUMN object_index.object_id IS '对象唯一标识';
COMMENT ON COLUMN object_index.first_seen IS '首次出现时间（首次打标）';
COMMENT ON COLUMN object_index.last_seen IS '最近出现时间（最近打标）';

-- ---------------------------------------------------------------------------
-- 幂等登记
-- ---------------------------------------------------------------------------
COMMENT ON TABLE biz_request_id IS '写入幂等登记（biz_request_id 去重，重复提交返回首次结果）';
COMMENT ON COLUMN biz_request_id.id IS '自增主键';
COMMENT ON COLUMN biz_request_id.biz_request_id IS '业务请求幂等号（调用方生成，全局唯一）';
COMMENT ON COLUMN biz_request_id.tenant_id IS '租户标识';
COMMENT ON COLUMN biz_request_id.status IS '处理状态：processing-处理中、done-已完成';
COMMENT ON COLUMN biz_request_id.response_json IS '首次成功处理的响应报文（重复请求原样返回）';
COMMENT ON COLUMN biz_request_id.created_at IS '首次受理时间';
COMMENT ON COLUMN biz_request_id.updated_at IS '最近更新时间';

-- ---------------------------------------------------------------------------
-- Outbox 事件
-- ---------------------------------------------------------------------------
COMMENT ON TABLE outbox_event IS 'Outbox 事件表（与写入同事务落库，供外部写入程序/下游消费）';
COMMENT ON COLUMN outbox_event.id IS '自增主键';
COMMENT ON COLUMN outbox_event.event_id IS '事件唯一标识（UUID）';
COMMENT ON COLUMN outbox_event.event_type IS '事件类型：TAG_VALUE_WRITTEN/TAG_VALUE_PENDING_AUDIT/TAG_VALUE_REVOKED';
COMMENT ON COLUMN outbox_event.tenant_id IS '租户标识';
COMMENT ON COLUMN outbox_event.payload IS '事件报文（JSON）';
COMMENT ON COLUMN outbox_event.status IS '投递状态：pending-待投递（当前仅落库，未内置投递器）';
COMMENT ON COLUMN outbox_event.retry_count IS '投递重试次数';
COMMENT ON COLUMN outbox_event.created_at IS '事件产生时间';
COMMENT ON COLUMN outbox_event.delivered_at IS '投递成功时间';

-- ---------------------------------------------------------------------------
-- 审计日志
-- ---------------------------------------------------------------------------
COMMENT ON TABLE audit_log IS '审计日志（控制台/开放接口操作留痕，保留 3 年）';
COMMENT ON COLUMN audit_log.id IS '自增主键';
COMMENT ON COLUMN audit_log.request_id IS '请求追踪 ID';
COMMENT ON COLUMN audit_log.actor IS '操作人标识（控制台用户或开放接口 AppKey）';
COMMENT ON COLUMN audit_log.actor_role IS '操作人角色：ADMIN/AUDITOR/VIEWER/SYSTEM/OPEN_API';
COMMENT ON COLUMN audit_log.action_type IS '操作类型（如 TAG_VALUE_WRITE、TAG_REGISTER、TENANT_CREATE）';
COMMENT ON COLUMN audit_log.tenant_id IS '租户标识';
COMMENT ON COLUMN audit_log.object_type IS '对象类型编码';
COMMENT ON COLUMN audit_log.object_id IS '对象唯一标识';
COMMENT ON COLUMN audit_log.tag_code IS '标签编码';
COMMENT ON COLUMN audit_log.detail IS '操作详情（JSON/文本）';
COMMENT ON COLUMN audit_log.result IS '操作结果：success-成功、failed-失败';
COMMENT ON COLUMN audit_log.source_ip IS '来源 IP';
COMMENT ON COLUMN audit_log.created_at IS '操作时间';

-- ---------------------------------------------------------------------------
-- 配额登记
-- ---------------------------------------------------------------------------
COMMENT ON TABLE quota IS '配额登记（MVP 演示读表，正式限流走 Redis 计数器）';
COMMENT ON COLUMN quota.id IS '自增主键';
COMMENT ON COLUMN quota.tenant_id IS '租户标识';
COMMENT ON COLUMN quota.quota_dim IS '配额维度（如 write_qps、read_qps）';
COMMENT ON COLUMN quota.limit_value IS '配额上限';
COMMENT ON COLUMN quota.used_value IS '已使用量';
COMMENT ON COLUMN quota.updated_at IS '更新时间';

-- ---------------------------------------------------------------------------
-- 控制台用户
-- ---------------------------------------------------------------------------
COMMENT ON TABLE console_user IS '控制台用户（轻量 RBAC：ADMIN/AUDITOR/VIEWER）';
COMMENT ON COLUMN console_user.id IS '自增主键';
COMMENT ON COLUMN console_user.username IS '登录用户名（唯一）';
COMMENT ON COLUMN console_user.password_hash IS '密码散列（pbkdf2:{iterations}:{salt}:{hash}；兼容种子数据 plain:{明文}）';
COMMENT ON COLUMN console_user.display_name IS '显示名称';
COMMENT ON COLUMN console_user.user_role IS '角色：ADMIN-管理员、AUDITOR-审核员、VIEWER-只读';
COMMENT ON COLUMN console_user.status IS '状态：enabled-启用，disabled-停用';
COMMENT ON COLUMN console_user.created_at IS '创建时间';

-- ---------------------------------------------------------------------------
-- 标签最新值宽表
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tag_value_latest IS '标签最新值宽表（画像查询数据源，由外部写入程序维护）';
COMMENT ON COLUMN tag_value_latest.tenant_id IS '租户标识';
COMMENT ON COLUMN tag_value_latest.object_type IS '对象类型：driver/vehicle/fleet/alarm_event';
COMMENT ON COLUMN tag_value_latest.object_id IS '对象唯一标识（司机ID/车牌号/车队ID/事件ID）';
COMMENT ON COLUMN tag_value_latest.tag_code IS '标签编码';
COMMENT ON COLUMN tag_value_latest.tag_value IS '标签值（统一按字符串存储）';
COMMENT ON COLUMN tag_value_latest.value_type IS '值类型：boolean/number/enum/string/date';
COMMENT ON COLUMN tag_value_latest.status IS '值状态：effective-生效、pending_audit-待审核、revoked-已作废、expired-已过期';
COMMENT ON COLUMN tag_value_latest.biz_time IS '业务发生时间';
COMMENT ON COLUMN tag_value_latest.operate_time IS '业务操作时间';
COMMENT ON COLUMN tag_value_latest.expire_time IS '值过期时间（空表示不过期）';
COMMENT ON COLUMN tag_value_latest.operator_id IS '操作人标识';
COMMENT ON COLUMN tag_value_latest.operator_name IS '操作人名称';
COMMENT ON COLUMN tag_value_latest.operator_type IS '操作人类型：human-人工、system-系统、algo-算法';
COMMENT ON COLUMN tag_value_latest.operator_org IS '操作人所属组织';
COMMENT ON COLUMN tag_value_latest.source IS '数据来源（open_api/console/import 等）';
COMMENT ON COLUMN tag_value_latest.confidence IS '置信度（0-1，算法标签使用）';
COMMENT ON COLUMN tag_value_latest.remark IS '备注/说明（敏感标签写入时必填）';
COMMENT ON COLUMN tag_value_latest.evidence_url IS '证据链接（图片/工单等）';
COMMENT ON COLUMN tag_value_latest.audit_by IS '审核人（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_latest.audit_time IS '审核时间（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_latest.audit_remark IS '审核意见（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_latest.request_id IS '请求追踪 ID';
COMMENT ON COLUMN tag_value_latest.op_time IS '记录写入时间（同对象+标签按此倒序取最新）';
COMMENT ON COLUMN tag_value_latest.ver IS '版本号（每次变更递增）';
COMMENT ON COLUMN tag_value_latest.ext_json IS '扩展字段（JSON）';

-- ---------------------------------------------------------------------------
-- 标签变更流水
-- ---------------------------------------------------------------------------
COMMENT ON TABLE tag_value_history IS '标签变更流水（追加式，永久保留，审计与血缘追溯）';
COMMENT ON COLUMN tag_value_history.id IS '自增主键';
COMMENT ON COLUMN tag_value_history.tenant_id IS '租户标识';
COMMENT ON COLUMN tag_value_history.object_type IS '对象类型：driver/vehicle/fleet/alarm_event';
COMMENT ON COLUMN tag_value_history.object_id IS '对象唯一标识（司机ID/车牌号/车队ID/事件ID）';
COMMENT ON COLUMN tag_value_history.tag_code IS '标签编码';
COMMENT ON COLUMN tag_value_history.tag_value IS '标签值（统一按字符串存储）';
COMMENT ON COLUMN tag_value_history.value_type IS '值类型：boolean/number/enum/string/date';
COMMENT ON COLUMN tag_value_history.status IS '值状态：effective-生效、pending_audit-待审核、revoked-已作废、expired-已过期';
COMMENT ON COLUMN tag_value_history.biz_time IS '业务发生时间';
COMMENT ON COLUMN tag_value_history.operate_time IS '业务操作时间';
COMMENT ON COLUMN tag_value_history.expire_time IS '值过期时间（空表示不过期）';
COMMENT ON COLUMN tag_value_history.operator_id IS '操作人标识';
COMMENT ON COLUMN tag_value_history.operator_name IS '操作人名称';
COMMENT ON COLUMN tag_value_history.operator_type IS '操作人类型：human-人工、system-系统、algo-算法';
COMMENT ON COLUMN tag_value_history.operator_org IS '操作人所属组织';
COMMENT ON COLUMN tag_value_history.source IS '数据来源（open_api/console/import 等）';
COMMENT ON COLUMN tag_value_history.confidence IS '置信度（0-1，算法标签使用）';
COMMENT ON COLUMN tag_value_history.remark IS '备注/说明（敏感标签写入时必填）';
COMMENT ON COLUMN tag_value_history.evidence_url IS '证据链接（图片/工单等）';
COMMENT ON COLUMN tag_value_history.audit_by IS '审核人（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_history.audit_time IS '审核时间（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_history.audit_remark IS '审核意见（历史字段，平台值审核已停用）';
COMMENT ON COLUMN tag_value_history.request_id IS '请求追踪 ID';
COMMENT ON COLUMN tag_value_history.op_time IS '记录写入时间（同对象+标签按此倒序取最新）';
COMMENT ON COLUMN tag_value_history.ver IS '版本号（每次变更递增）';
COMMENT ON COLUMN tag_value_history.ext_json IS '扩展字段（JSON）';
