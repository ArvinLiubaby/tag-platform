package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.TaskWriteLinkHook;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import com.tagplatform.workbench.TaskModels.AcceptRequest;
import com.tagplatform.workbench.TaskModels.CreateRequest;
import com.tagplatform.workbench.TaskModels.ItemValue;
import com.tagplatform.workbench.TaskModels.ReviewRequest;
import com.tagplatform.workbench.TaskModels.SubmitRequest;
import com.tagplatform.workbench.TaskModels.SubmitResponse;
import com.tagplatform.workbench.TaskModels.SubmitTag;
import com.tagplatform.workbench.TaskModels.TaskDetail;
import com.tagplatform.workbench.TaskModels.TaskItemView;
import com.tagplatform.workbench.TaskModels.TaskView;
import com.tagplatform.workbench.TaskModels.WorkbenchItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工作台/任务闭环集成测试（demo 形态）：
 * 创建 → 发布 → 池内领取 → 普通标签直写 / 敏感标签先审后写 → 任务完成。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_wb_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_wb_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class WorkbenchFlowIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private TaskService taskService;

    @Autowired
    private WorkbenchService workbenchService;

    @Autowired
    private TaskRepository taskRepository;

    @Autowired
    private ConsoleUserRepository userRepository;

    @Autowired
    private TagValueStore tagValueStore;

    @BeforeEach
    void setUp() {
        if (userRepository.findByUsername("tagger_it").isEmpty()) {
            userRepository.insert("tagger_it", "plain:Tagger123", "集成测试打标员", "TAGGER",
                    TENANT, "CS9999", "集成测试打标员", "客服中心", "客服一组");
        }
        if (userRepository.findByUsername("tenant_admin_it").isEmpty()) {
            userRepository.insert("tenant_admin_it", "plain:Tenant123", "集成测试租户管理员", "TENANT_ADMIN",
                    TENANT, "TA9999", "集成测试租户管理员", "风控平台", "风控一组");
        }
        adminContext();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private void adminContext() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }

    private void tenantAdminContext() {
        RequestContext.setConsoleUser("tenant_admin_it", "TENANT_ADMIN", "集成测试租户管理员", TENANT,
                "TA9999", "集成测试租户管理员", "风控平台", "风控一组");
    }

    private void taggerContext() {
        RequestContext.setConsoleUser("tagger_it", "TAGGER", "集成测试打标员", TENANT,
                "CS9999", "集成测试打标员", "客服中心", "客服一组");
    }

    @Test
    void normalTagTaskFlowsToCompletion() {
        String objectId = "DRV95001";
        TaskView created = taskService.create(new CreateRequest(TENANT, "普通标签任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(objectId), false, true));
        assertThat(created.task().status()).isEqualTo("published");
        assertThat(created.stats().pending()).isEqualTo(1);

        taggerContext();
        assertThat(workbenchService.myItems(null, 50)).anyMatch(x -> x.item().objectId().equals(objectId));
        WorkbenchItem todo = workbenchService.myItems(null, 50).stream()
                .filter(x -> x.item().objectId().equals(objectId)).findFirst().orElseThrow();
        long itemId = todo.item().id();

        SubmitResponse submitted = workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                itemId, "driver", objectId, List.of(new SubmitTag("risk_level", "high", "回访确认", null, null))));
        assertThat(submitted.status()).isEqualTo("effective");

        TaskItemView item = taskService.itemView(itemId);
        assertThat(item.item().status()).isEqualTo("approved");
        assertThat(item.values()).extracting(ItemValue::status).containsExactly("approved");

        var history = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, "CS9999", "driver", objectId, "risk_level", null, null, 0, 10));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).status()).isEqualTo(TagValue.STATUS_EFFECTIVE);
        assertThat(history.get(0).source()).isEqualTo("workbench_task");
        assertThat(history.get(0).tagValue()).isEqualTo("high");

        adminContext();
        TaskDetail detail = taskService.detail(created.task().id());
        assertThat(detail.task().status()).isEqualTo("completed");
        assertThat(detail.stats().approved()).isEqualTo(1);
    }

    @Test
    void sensitiveTagTaskRequiresReviewBeforeWrite() {
        String objectId = "DRV95002";
        TaskView created = taskService.create(new CreateRequest(TENANT, "敏感标签任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("suspected_fraud"), List.of(objectId), false, true));
        assertThat(created.task().reviewRequired()).isTrue();
        assertThat(created.task().status()).isEqualTo("published");

        taggerContext();
        WorkbenchItem todo = workbenchService.myItems(null, 50).stream()
                .filter(x -> x.item().objectId().equals(objectId)).findFirst().orElseThrow();
        long itemId = todo.item().id();
        SubmitResponse submitted = workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                itemId, "driver", objectId,
                List.of(new SubmitTag("suspected_fraud", "true", "回访发现异常", null, null))));
        assertThat(submitted.status()).isEqualTo("pending_review");

        assertThat(tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "suspected_fraud", null, null, 0, 10))).isEmpty();

        adminContext();
        TaskItemView reviewed = taskService.review(created.task().id(), itemId,
                new ReviewRequest(true, "审核通过"));
        assertThat(reviewed.item().status()).isEqualTo("approved");
        assertThat(reviewed.values()).extracting(ItemValue::status).containsExactly("approved");

        var history = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "suspected_fraud", null, null, 0, 10));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).auditBy()).isEqualTo("平台管理员");
        assertThat(history.get(0).status()).isEqualTo(TagValue.STATUS_EFFECTIVE);

        TaskDetail detail = taskService.detail(created.task().id());
        assertThat(detail.task().status()).isEqualTo("completed");
    }

    @Test
    void tenantAdminCanCreateTaskWithinTenant() {
        RequestContext.setConsoleUser("tenant_admin_it", "TENANT_ADMIN", "集成测试租户管理员",
                TENANT, "TA9999", "集成测试租户管理员", "风控平台", "风控一组");
        TaskView created = taskService.create(new CreateRequest(TENANT, "租户管理员建任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of("DRV95006"), false, true));
        assertThat(created.task().tenantId()).isEqualTo(TENANT);
        assertThat(created.task().status()).isEqualTo("published");
    }

    @Test
    void allObjectsTaskSnapshotsObjectIndex() {
        String objectId = "DRV95005";
        taskService.create(new CreateRequest(TENANT, "对象索引预热", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(objectId), false, false));

        TaskView created = taskService.create(new CreateRequest(TENANT, "全部对象任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(), true, true));
        assertThat(created.task().totalItems()).isGreaterThanOrEqualTo(1);
        TaskDetail detail = taskService.detail(created.task().id());
        assertThat(detail.items()).anyMatch(x -> x.item().objectId().equals(objectId));
    }

    @Test
    void rejectedItemCanBeReworkedAndApproved() {
        String objectId = "DRV95004";
        TaskView created = taskService.create(new CreateRequest(TENANT, "驳回返工任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("suspected_fraud"), List.of(objectId), false, true));
        taggerContext();
        WorkbenchItem todo = workbenchService.myItems(null, 50).stream()
                .filter(x -> x.item().objectId().equals(objectId)).findFirst().orElseThrow();
        long itemId = todo.item().id();
        workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(), itemId, "driver", objectId,
                List.of(new SubmitTag("suspected_fraud", "false", "初次判断", null, null))));

        adminContext();
        TaskItemView rejected = taskService.review(created.task().id(), itemId,
                new ReviewRequest(false, "证据不足，请补充"));
        assertThat(rejected.item().status()).isEqualTo("rejected");
        assertThat(rejected.values()).extracting(ItemValue::status).containsExactly("rejected");
        assertThat(tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "suspected_fraud", null, null, 0, 10))).isEmpty();

        taggerContext();
        SubmitResponse reworked = workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                itemId, "driver", objectId,
                List.of(new SubmitTag("suspected_fraud", "true", "已补充回访录音", null, null))));
        assertThat(reworked.status()).isEqualTo("pending_review");

        adminContext();
        TaskItemView approved = taskService.review(created.task().id(), itemId,
                new ReviewRequest(true, "证据充分"));
        assertThat(approved.item().status()).isEqualTo("approved");
        var history = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "suspected_fraud", null, null, 0, 10));
        assertThat(history).hasSize(1);
        assertThat(history.get(0).tagValue()).isEqualTo("true");
    }

    @Test
    void adhocSensitiveTagCreatesReviewTask() {
        String objectId = "DRV95003";
        tenantAdminContext();
        SubmitResponse submitted = workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                null, "driver", objectId,
                List.of(new SubmitTag("suspected_fraud", "false", "临时补标", null, null))));
        assertThat(submitted.status()).isEqualTo("pending_review");
        assertThat(submitted.taskItemId()).isNotNull();

        TaskModels.TaskItemContext context = taskService.itemContext(submitted.taskItemId());
        assertThat(context.task().taskType()).isEqualTo("adhoc");
        assertThat(context.item().status()).isEqualTo("submitted");

        adminContext();
        taskService.review(context.task().id(), context.item().id(), new ReviewRequest(true, "通过"));
        assertThat(tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "suspected_fraud", null, null, 0, 10))).hasSize(1);
    }

    @Test
    void completedTaskCanBeVerifiedAndLocksAdhoc() {
        String objectId = "DRV95006";
        adminContext();
        TaskView created = taskService.create(new CreateRequest(TENANT, "验收流程任务", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(objectId), false, true));
        long taskId = created.task().id();

        taggerContext();
        WorkbenchItem todo = workbenchService.myItems(null, 50).stream()
                .filter(x -> x.item().objectId().equals(objectId)).findFirst().orElseThrow();
        workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                todo.item().id(), "driver", objectId,
                List.of(new SubmitTag("risk_level", "high", "正常打标", null, null))));

        adminContext();
        assertThat(taskService.detail(taskId).task().status()).isEqualTo("completed");

        taggerContext();
        assertThatThrownBy(() -> taskService.accept(taskId, new AcceptRequest("越权验收")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));

        tenantAdminContext();
        TaskView verified = taskService.accept(taskId, new AcceptRequest("抽查通过"));
        assertThat(verified.task().status()).isEqualTo("verified");

        assertThatThrownBy(() -> workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                null, "driver", objectId,
                List.of(new SubmitTag("risk_level", "low", "验收后补标", null, null)))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.TASK_VERIFIED_LOCK));

        assertThatThrownBy(() -> taskService.accept(taskId, new AcceptRequest("再次验收")))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void externalWriteWithTaskIdAutoCompletesTask() {
        String objectId = "DRV95007";
        adminContext();
        TaskView created = taskService.create(new CreateRequest(TENANT, "外部写入推进", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level", "alarm_count_30d"), List.of(objectId), false, true));
        long taskId = created.task().id();

        taskService.validateExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, "risk_level", "high", "回访", "高", null)));
        taskService.applyExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, "risk_level", "high", "回访", "高", null)));
        assertThat(taskRepository.findItemByObject(taskId, objectId).orElseThrow().status())
                .isEqualTo("in_progress");

        taskService.applyExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, "alarm_count_30d", "5", null, null, null)));
        assertThat(taskRepository.findItemByObject(taskId, objectId).orElseThrow().status())
                .isEqualTo("approved");
        assertThat(taskService.detail(taskId).task().status()).isEqualTo("completed");

        assertThatThrownBy(() -> taskService.validateExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, "eye_size", "大", null, null, null))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
        assertThatThrownBy(() -> taskService.validateExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", "DRV95999", "risk_level", "high", null, null, null))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void externalWriteRespectsReviewRequired() {
        String objectId = "DRV95008";
        adminContext();
        TaskView created = taskService.create(new CreateRequest(TENANT, "外部写入审核", "集成测试",
                "driver", "normal", "pool", null, null, null, true, null,
                List.of("risk_level"), List.of(objectId), false, true));
        long taskId = created.task().id();
        taskService.applyExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, "risk_level", "high", "外部写入", null, null)));
        long itemId = taskRepository.findItemByObject(taskId, objectId).orElseThrow().id();
        assertThat(taskRepository.findItem(itemId).orElseThrow().status()).isEqualTo("submitted");
        assertThat(taskService.detail(taskId).task().status()).isEqualTo("reviewing");

        adminContext();
        taskService.review(taskId, itemId, new ReviewRequest(true, "确认外部写入"));
        assertThat(taskRepository.findItem(itemId).orElseThrow().status()).isEqualTo("approved");
        assertThat(taskService.detail(taskId).task().status()).isEqualTo("completed");
        // 外部写入已落库，审核通过不应重复写 history
        assertThat(tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                TENANT, null, "driver", objectId, "risk_level", null, null, 0, 10))).isEmpty();
    }

    @Test
    void openTaskQueriesSupportOperatorAndObjectFilters() {
        String objectId = "DRV95010";
        adminContext();
        TaskView created = taskService.create(new CreateRequest(TENANT, "客服任务查询", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(objectId), false, true));
        long taskId = created.task().id();

        taggerContext();
        WorkbenchItem todo = workbenchService.myItems(null, 50).stream()
                .filter(x -> x.item().objectId().equals(objectId)).findFirst().orElseThrow();
        workbenchService.claim(todo.item().id());

        var pageAll = taskService.openList(TENANT, null, null, null, 1, 50);
        assertThat(pageAll.records()).anyMatch(v -> v.task().id() == taskId);

        var pageOperator = taskService.openList(TENANT, null, "CS9999", null, 1, 50);
        assertThat(pageOperator.records()).anyMatch(v -> v.task().id() == taskId
                && v.myStats() != null && v.myStats().claimed() == 1);
        assertThat(pageOperator.records().stream()
                .filter(v -> v.task().id() == taskId).findFirst().orElseThrow().myStats().total())
                .isEqualTo(1);

        var pageObject = taskService.openList(TENANT, null, "CS9999", objectId, 1, 50);
        assertThat(pageObject.records()).anyMatch(v -> v.task().id() == taskId
                && v.myStats() != null && v.myStats().total() == 1);

        assertThat(taskService.openList(TENANT, null, "NOBODY", null, 1, 50).total()).isZero();

        var items = taskService.openTaskItems(TENANT, null, "CS9999", objectId, null, 1, 20, true);
        assertThat(items.total()).isEqualTo(1);
        assertThat(items.records().get(0).taskId()).isEqualTo(taskId);
        assertThat(items.records().get(0).taskCode()).isEqualTo(created.task().taskCode());
        assertThat(items.records().get(0).item().status()).isEqualTo("claimed");
    }

    @Test
    void openTaskQueriesSupportObjectTypeFilter() {
        String driverId = "DRV95012";
        String plate = "苏A95012";
        adminContext();
        TaskView driverTask = taskService.create(new CreateRequest(TENANT, "对象过滤-司机", "集成测试",
                "driver", "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of(driverId), false, true));
        TaskView vehicleTask = taskService.create(new CreateRequest(TENANT, "对象过滤-车辆", "集成测试",
                "vehicle", "normal", "pool", null, null, null, false, null,
                List.of("high_mileage"), List.of(plate), false, true));

        // type + id 精确配对：各自只命中自己的任务
        var byDriver = taskService.openList(TENANT, null, null, "driver", driverId, null, 1, 50);
        assertThat(byDriver.records()).extracting(v -> v.task().id())
                .containsExactly(driverTask.task().id());
        var byVehicle = taskService.openList(TENANT, null, null, "vehicle", plate, null, 1, 50);
        assertThat(byVehicle.records()).extracting(v -> v.task().id())
                .containsExactly(vehicleTask.task().id());

        // 多类型按位置配对：任一对象命中即返回该任务
        var both = taskService.openList(TENANT, null, null, "driver,vehicle", driverId + "," + plate, null, 1, 50);
        assertThat(both.records()).extracting(v -> v.task().id())
                .containsExactlyInAnyOrder(driverTask.task().id(), vehicleTask.task().id());

        // 单类型 + 多 ID
        var multiId = taskService.openList(TENANT, null, null, "driver", driverId + ",DRV99999", null, 1, 50);
        assertThat(multiId.records()).extracting(v -> v.task().id())
                .containsExactly(driverTask.task().id());

        // 只传 object_type：该类型对象参与的任务
        var typeOnly = taskService.openList(TENANT, null, null, "vehicle", null, null, 1, 50);
        assertThat(typeOnly.records()).extracting(v -> v.task().id())
                .contains(vehicleTask.task().id());

        // 只传 object_id（不带 type）：v1.21.0 兼容语义，不限定类型按 ID 匹配
        var idOnly = taskService.openList(TENANT, null, null, null, plate, null, 1, 50);
        assertThat(idOnly.records()).extracting(v -> v.task().id())
                .containsExactly(vehicleTask.task().id());

        // 类型与 ID 个数不等（类型数 > 1）→ 2002
        assertThatThrownBy(() -> taskService.openList(TENANT, null, null, "driver,vehicle", driverId, null, 1, 50))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.PARAM_INVALID));
    }

    @Test
    void taggerCannotSubmitAdhoc() {
        taggerContext();
        assertThatThrownBy(() -> workbenchService.submit(new SubmitRequest(UUID.randomUUID().toString(),
                null, "driver", "DRV95004",
                List.of(new SubmitTag("suspected_fraud", "false", "越权补标", null, null)))))
                .isInstanceOfSatisfying(BizException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo(ErrorCode.CONSOLE_FORBIDDEN));
    }
}
