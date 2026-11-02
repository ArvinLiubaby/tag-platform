package com.tagplatform.workbench;

import com.tagplatform.common.RequestContext;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
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
        taggerContext();
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
                TENANT, "CS9999", "driver", objectId, "suspected_fraud", null, null, 0, 10))).hasSize(1);
    }
}
