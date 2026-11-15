package com.tagplatform.write;

import com.tagplatform.audit.AuditValueService;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.meta.service.TagMetaService;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.ProfileView;
import com.tagplatform.write.service.TagValueWriteService;
import com.tagplatform.write.store.TagValueStore;
import com.tagplatform.write.web.dto.DeleteRequest;
import com.tagplatform.write.web.dto.OperatorDto;
import com.tagplatform.write.web.dto.WriteRequest;
import com.tagplatform.write.web.dto.WriteResult;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 写入链路集成测试（demo 形态：H2 内存库 + 内存 KV）：
 * 直通生效 / 敏感标签转审核 → 通过 / 幂等回放 / 作废 / 校验失败整批拒绝。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class WriteFlowIntegrationTest {

    private static final String TENANT = "risk_control";
    private static final OperatorDto OPERATOR = new OperatorDto("OP1001", "张三", "human", "某交警支队");

    @Autowired
    private TagValueWriteService writeService;

    @Autowired
    private AuditValueService auditValueService;

    @Autowired
    private ProfileQueryService profileQueryService;

    @Autowired
    private TagMetaService tagMetaService;

    @Autowired
    private TagMetaRepository tagMetaRepository;

    @Autowired
    private TagValueStore tagValueStore;

    private WriteRequest.WriteItem writeItem(String objectId, String tagCode, String value, String remark) {
        // 用相对当前时间：expire_time = biz_time + 标签 TTL，写死历史日期会在某天集体过期，
        // 导致画像按设计过滤掉过期值、测试突然失败（2026-11-13 踩过一次）。
        LocalDateTime bizTime = LocalDateTime.now().minusHours(1);
        return new WriteRequest.WriteItem("driver", objectId, tagCode, value,
                bizTime, bizTime.plusMinutes(1),
                OPERATOR, "it-test", "高", remark, null, null);
    }

    private WriteResult write(String bizRequestId, WriteRequest.WriteItem... items) {
        return writeService.write(new WriteRequest(bizRequestId, List.of(items)), TENANT, "req-" + bizRequestId);
    }

    /** 模拟外部程序：消费该对象+标签的最新一条流水并 upsert 到 latest */
    private void syncLatest(String objectType, String objectId, String tagCode) {
        var rows = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, objectType, objectId, tagCode, null, null, 0, 1));
        assertThat(rows).isNotEmpty();
        tagValueStore.upsertLatest(rows.get(0));
    }

    @Test
    void writeEffectiveTagThenQueryProfile() {
        String objectId = "DRV90001";
        WriteResult result = write(UUID.randomUUID().toString(), writeItem(objectId, "risk_level", "high", null));

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.accepted()).isEqualTo(1);
        assertThat(result.pendingAudit()).isZero();
        assertThat(result.results().get(0).status()).isEqualTo("accepted");

        // 写入接口不再自动更新 latest
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).isEmpty();

        // 模拟外部程序消费流水并写 latest
        syncLatest("driver", objectId, "risk_level");

        var profile = profileQueryService.profile(TENANT, "driver", objectId, false);
        assertThat(profile.tags()).hasSize(1);
        assertThat(profile.tags().get(0).tagCode()).isEqualTo("risk_level");
        assertThat(profile.tags().get(0).tagValue()).isEqualTo("high");
        assertThat(profile.tags().get(0).status()).isEqualTo("effective");
        assertThat(profile.tags().get(0).tagName()).isEqualTo("风险等级");
    }

    @Test
    void sensitiveTagGoesPendingAndAuditIsDisabled() {
        String objectId = "DRV90002";
        WriteResult result = write(UUID.randomUUID().toString(),
                writeItem(objectId, "suspected_fraud", "true", "集成测试：疑似骗保"));
        assertThat(result.pendingAudit()).isEqualTo(1);
        assertThat(result.results().get(0).status()).isEqualTo("pending_audit");

        // 写入接口不再自动更新 latest，同步前两种查询都为空
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).isEmpty();
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, true).tags()).isEmpty();

        // 模拟外部程序写 latest 后，默认不返回 pending，includePending 可见
        syncLatest("driver", objectId, "suspected_fraud");
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).isEmpty();
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, true).tags())
                .as("latest=%s", tagValueStore.listLatestByObject(TENANT, "driver", objectId))
                .hasSize(1);

        // 待审核队列仍可只读查询
        assertThat(auditValueService.pending(100))
                .anyMatch(v -> objectId.equals(v.objectId()) && "pending_audit".equals(v.status()));

        // 审核已停用：通过/驳回均返回 3013
        assertThatThrownBy(() -> auditValueService.approve(TENANT, "driver", objectId, "suspected_fraud",
                "平台管理员", "复核通过"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AUDIT_DISABLED));
        assertThatThrownBy(() -> auditValueService.reject(TENANT, "driver", objectId, "suspected_fraud",
                "平台管理员", "复核驳回"))
                .isInstanceOf(BizException.class)
                .satisfies(e -> assertThat(((BizException) e).getErrorCode()).isEqualTo(ErrorCode.AUDIT_DISABLED));
    }

    @Test
    void idempotentReplayReturnsSameResult() {
        String objectId = "DRV90003";
        String bizRequestId = UUID.randomUUID().toString();
        WriteResult first = write(bizRequestId, writeItem(objectId, "risk_level", "medium", null));
        WriteResult replay = write(bizRequestId, writeItem(objectId, "risk_level", "medium", null));

        assertThat(replay.total()).isEqualTo(first.total());
        assertThat(replay.accepted()).isEqualTo(first.accepted());
        assertThat(replay.bizRequestId()).isEqualTo(bizRequestId);
    }

    @Test
    void deleteRevokesAndIsIdempotent() {
        String objectId = "DRV90004";
        write(UUID.randomUUID().toString(), writeItem(objectId, "risk_level", "low", null));
        syncLatest("driver", objectId, "risk_level");
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).hasSize(1);

        DeleteRequest.DeleteItem deleteItem = new DeleteRequest.DeleteItem("driver", objectId, "risk_level",
                OPERATOR, "集成测试作废");
        WriteResult deleted = writeService.delete(
                new DeleteRequest(UUID.randomUUID().toString(), List.of(deleteItem)), TENANT, "req-del");
        assertThat(deleted.results().get(0).status()).isEqualTo("revoked");

        // 作废只写流水，latest 由外部程序同步；同步前仍是原值
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).hasSize(1);
        syncLatest("driver", objectId, "risk_level");
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).isEmpty();

        // 再次作废幂等返回 revoked
        WriteResult again = writeService.delete(
                new DeleteRequest(UUID.randomUUID().toString(), List.of(deleteItem)), TENANT, "req-del-2");
        assertThat(again.results().get(0).status()).isEqualTo("revoked");
    }

    @Test
    void invalidItemRejectsWholeBatch() {
        String objectId = "DRV90005";
        // 第二条枚举值非法 → 整批拒绝，且第一条不得入库（all-or-nothing）
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                writeItem(objectId, "risk_level", "high", null),
                writeItem(objectId, "risk_level", "critical", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("第 2 条");
        assertThat(profileQueryService.profile(TENANT, "driver", objectId, false).tags()).isEmpty();
        assertThat(tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, null, null, null, 0, 10))).isEmpty();
    }

    @Test
    void unregisteredObjectTypeRejected() {
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("unknown_type", "X1", "risk_level", "high",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null)))
                .isInstanceOf(BizException.class);
    }

    @Test
    void objectIdPatternNotEnforced() {
        // 2026-11-11 起取消 id_pattern 校验：不像 DRVxxxxx 的 ID 也照常受理
        WriteResult result = write(UUID.randomUUID().toString(),
                writeItem("BAD-ID-123", "risk_level", "high", null));
        assertThat(result.accepted()).isEqualTo(1);
    }

    @Test
    void blankObjectIdStillRejected() {
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                writeItem("   ", "risk_level", "high", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("object_id 不能为空");
    }

    @Test
    void tagObjectTypeMismatchRejected() {
        // risk_level 绑定 driver（V4 迁移回填），写入 vehicle 应被拒（3011 标签不适用于该对象类型）
        String plate = "苏A88881";
        assertThatThrownBy(() -> writeService.write(new WriteRequest(UUID.randomUUID().toString(),
                List.of(new WriteRequest.WriteItem("vehicle", plate, "risk_level", "high",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null))),
                TENANT, "req-mismatch"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不适用于");
    }

    @Test
    void registerTagWithObjectBinding() {
        // 注册带 object_type（多值含空格，应归一化）+ source_mode 的标签
        var meta = tagMetaService.register(new TagMetaService.RegisterRequest(
                "binding_demo_tag", "绑定演示", "risk_score", "boolean", null, "fact",
                false, 7, TENANT, "L2", "driver, vehicle", "manual", null, null), "集成测试");
        assertThat(meta.objectType()).isEqualTo("driver,vehicle");
        assertThat(meta.sourceMode()).isEqualTo("manual");
        assertThat(meta.writable()).isTrue(); // 未传 writable 默认允许写入

        // 持久化回读（MAPPER 映射正确性）
        var stored = tagMetaRepository.findByCode("binding_demo_tag").orElseThrow();
        assertThat(stored.objectType()).isEqualTo("driver,vehicle");
        assertThat(stored.sourceMode()).isEqualTo("manual");
        assertThat(stored.writable()).isTrue();
        assertThat(stored.supportsObjectType("driver")).isTrue();
        assertThat(stored.supportsObjectType("vehicle")).isTrue();
        assertThat(stored.supportsObjectType("fleet")).isFalse();

        // source_mode 非枚举值 → 拒绝
        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "binding_bad_mode", "非法来源", "risk_score", "boolean", null, "fact",
                false, 7, TENANT, "L2", null, "auto", null, null), "集成测试"))
                .isInstanceOf(BizException.class).hasMessageContaining("source_mode");

        // object_type 未注册 → 拒绝
        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "binding_bad_obj", "非法对象", "risk_score", "boolean", null, "fact",
                false, 7, TENANT, "L2", "spaceship", null, null, null), "集成测试"))
                .isInstanceOf(BizException.class).hasMessageContaining("object_type 未注册");
    }

    @Test
    void writableSwitchGatesWriteAndDelete() {
        String plate = "苏A88882";
        // 注册时显式关闭写入开关
        tagMetaService.register(new TagMetaService.RegisterRequest(
                "writable_gate", "写入开关演示", "risk_score", "boolean", null, "fact",
                false, 7, TENANT, "L2", "vehicle", "manual", null, false), "集成测试");
        tagMetaRepository.updateStatus("writable_gate", "online");
        assertThat(tagMetaRepository.findByCode("writable_gate").orElseThrow().writable()).isFalse();

        // writable=false：推送写入被拒（code=3012，提示不允许写入）
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "writable_gate", "true",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许写入")
                .satisfies(e -> assertThat(((BizException) e).getErrorCode())
                        .isEqualTo(ErrorCode.TAG_WRITE_DISABLED));

        // writable=false：作废同样被拒
        assertThatThrownBy(() -> writeService.delete(new DeleteRequest(UUID.randomUUID().toString(),
                List.of(new DeleteRequest.DeleteItem("vehicle", plate, "writable_gate", OPERATOR, "开关测试作废"))),
                TENANT, "req-writable-del"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许写入");

        // 控制台开启开关后写入直通
        tagMetaService.changeWritable("writable_gate", true);
        WriteResult resumed = write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "writable_gate", "true",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null));
        assertThat(resumed.accepted()).isEqualTo(1);

        // 再次关闭：即时生效
        tagMetaService.changeWritable("writable_gate", false);
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "writable_gate", "false",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不允许写入");
        tagMetaService.changeWritable("writable_gate", true); // 收尾恢复
    }

    @Test
    void batchProfileForDriverAndVehicle() {
        String plate = "苏A88883";
        write(UUID.randomUUID().toString(),
                writeItem("DRV91001", "risk_level", "high", null),
                new WriteRequest.WriteItem("vehicle", plate, "high_mileage", "120000",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null));

        // 写入接口不更新 latest；查询前由外部程序同步
        assertThat(profileQueryService.profile(TENANT, "driver", "DRV91001", false).tags()).isEmpty();
        syncLatest("driver", "DRV91001", "risk_level");
        syncLatest("vehicle", plate, "high_mileage");

        // 统一入口：多类型混合对象（driver,vehicle 按位置配对）+ 空占位，替代原组合画像接口
        var batch = profileQueryService.profiles(TENANT, "driver,vehicle", "DRV91001," + plate,
                false, true, Set.of());
        assertThat(batch.total()).isEqualTo(2);
        var driverTags = batch.records().get(0).tags();
        var vehicleTags = batch.records().get(1).tags();

        // risk_control 可见且适配该对象的标签：driver 7 个（6 个无值占位，含人员基础属性 3 个）/ vehicle 2 个（1 个占位）
        assertThat(driverTags).hasSize(7);
        assertThat(vehicleTags).hasSize(2);

        // 有值项带字典元数据（source_mode 来自 V4 回填）
        assertThat(driverTags).anyMatch(t -> "risk_level".equals(t.tagCode())
                && "high".equals(t.tagValue()) && "effective".equals(t.status())
                && "hybrid".equals(t.sourceMode()));
        assertThat(vehicleTags).anyMatch(t -> "high_mileage".equals(t.tagCode())
                && "120000".equals(t.tagValue()));

        // 无值占位：status=none、值为 null、保留 need_value_audit 标记
        assertThat(driverTags).anyMatch(t -> "suspected_fraud".equals(t.tagCode())
                && ProfileView.STATUS_NONE.equals(t.status()) && t.tagValue() == null && t.needValueAudit());

        // 对象适配过滤：vehicle 绑定标签不出现在 driver 段；fleet 绑定标签两段均不出现
        assertThat(driverTags).noneMatch(t -> "plate_blacklist".equals(t.tagCode()));
        assertThat(driverTags).noneMatch(t -> "fleet_risk".equals(t.tagCode()));
        assertThat(vehicleTags).noneMatch(t -> "fleet_risk".equals(t.tagCode()));

        // 单类型单对象 + include_empty=false → 只返回有值项
        var vehicleOnly = profileQueryService.profiles(TENANT, "vehicle", plate, false, false, Set.of());
        assertThat(vehicleOnly.total()).isEqualTo(1);
        assertThat(vehicleOnly.records().get(0).tags()).hasSize(1);
        assertThat(vehicleOnly.records().get(0).tags().get(0).tagCode()).isEqualTo("high_mileage");

        // tag_codes 精确过滤（清单-1015 场景）：仅返回指定标签；无值仍以 none 占位；不适配标签不出现在该段
        var filtered = profileQueryService.profiles(TENANT, "driver,vehicle", "DRV91001," + plate,
                false, true, Set.of("risk_level", "driver_score", "high_mileage", "plate_blacklist"));
        assertThat(filtered.records().get(0).tags()).extracting(ProfileView.TagItem::tagCode)
                .containsExactlyInAnyOrder("risk_level", "driver_score");
        assertThat(filtered.records().get(1).tags()).extracting(ProfileView.TagItem::tagCode)
                .containsExactlyInAnyOrder("high_mileage", "plate_blacklist");
        assertThat(filtered.records().get(0).tags()).anyMatch(t -> "driver_score".equals(t.tagCode())
                && ProfileView.STATUS_NONE.equals(t.status()));

        // object_id 缺失 → 参数错误
        assertThatThrownBy(() -> profileQueryService.profiles(TENANT, "driver", null, false, true, Set.of()))
                .isInstanceOf(BizException.class).hasMessageContaining("object_id");
    }

    @Test
    void remarkRequiredForAuditTag() {
        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                writeItem("DRV90006", "suspected_fraud", "true", null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("remark");
    }

    @Test
    void multiSelectEnumEnforcedAndNormalized() {
        String plate = "苏A88884";
        var meta = tagMetaService.register(new TagMetaService.RegisterRequest(
                "multi_env_demo", "多选环境演示", "work_env", "enum",
                "{\"enum\":[\"公共道路\",\"港口\",\"矿区\"]}", "fact", false, 0,
                TENANT, "L2", "vehicle", "manual", true, true), "集成测试");
        assertThat(meta.valueMultiple()).isTrue();
        tagMetaRepository.updateStatus("multi_env_demo", "online");

        WriteResult accepted = write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "multi_env_demo", " 公共道路 , 港口 ",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", "高", null, null, null));
        assertThat(accepted.accepted()).isEqualTo(1);

        var history = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "vehicle", plate, "multi_env_demo", null, null, 0, 1));
        assertThat(history).isNotEmpty();
        assertThat(history.get(0).tagValue()).isEqualTo("公共道路,港口");

        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "multi_env_demo", "公共道路,沙漠",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("多选值不在允许集合内");

        assertThatThrownBy(() -> write(UUID.randomUUID().toString(),
                new WriteRequest.WriteItem("vehicle", plate, "multi_env_demo", "港口,港口",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null)))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("多选值重复");

        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "bad_multi_demo", "非法多选", "work_env", "enum", null, "fact", false, 0,
                TENANT, "L2", "vehicle", "manual", true, true), "集成测试"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("value_range.enum");
    }

    @Test
    void tagDefinitionSharedAcrossTenants() {
        var meta = tagMetaService.register(new TagMetaService.RegisterRequest(
                "shared_demo", "共享标签", "risk_score", "boolean", null, "fact", false, 0,
                "risk_control, system_a", "L2", "fleet", "manual", false, true), "集成测试");
        assertThat(meta.tenantId()).isEqualTo("risk_control,system_a");
        tagMetaRepository.updateStatus("shared_demo", "online");

        assertThat(meta.visibleTo("system_a")).isTrue();
        assertThat(meta.visibleTo("insurance_claim")).isFalse();
        assertThat(tagMetaService.listVisibleOnline("system_a"))
                .anyMatch(t -> "shared_demo".equals(t.tagCode()));
        assertThat(tagMetaService.listVisibleOnline("insurance_claim"))
                .noneMatch(t -> "shared_demo".equals(t.tagCode()));

        WriteResult result = writeService.write(new WriteRequest(UUID.randomUUID().toString(),
                List.of(new WriteRequest.WriteItem("fleet", "FLT9001", "shared_demo", "true",
                        LocalDateTime.now(), LocalDateTime.now(), OPERATOR, "it-test", null, null, null, null))),
                "system_a", "req-shared");
        assertThat(result.accepted()).isEqualTo(1);

        var publicTag = tagMetaService.register(new TagMetaService.RegisterRequest(
                "public_demo", "公开标签", "risk_score", "boolean", null, "fact", false, 0,
                "PUBLIC,risk_control", "L2", "fleet", "manual", false, true), "集成测试");
        assertThat(publicTag.tenantId()).isEqualTo("PUBLIC");
        tagMetaRepository.updateStatus("public_demo", "online");
        assertThat(tagMetaService.listVisibleOnline("insurance_claim"))
                .anyMatch(t -> "public_demo".equals(t.tagCode()));

        assertThatThrownBy(() -> tagMetaService.register(new TagMetaService.RegisterRequest(
                "bad_tenant_demo", "非法租户", "risk_score", "boolean", null, "fact", false, 0,
                "no_such_tenant", "L2", "driver", "manual", false, true), "集成测试"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("租户不存在");
    }
}
