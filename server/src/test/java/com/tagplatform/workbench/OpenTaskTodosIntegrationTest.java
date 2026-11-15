package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.common.TaskWriteLinkHook;
import com.tagplatform.workbench.TaskModels.CreateRequest;
import com.tagplatform.workbench.TaskModels.ExternalProgressItem;
import com.tagplatform.workbench.TaskModels.ExternalProgressRequest;
import com.tagplatform.workbench.TaskModels.OpenTaskTodoPage;
import com.tagplatform.workbench.TaskModels.OpenTaskTodoView;
import com.tagplatform.workbench.TaskModels.ReviewRequest;
import com.tagplatform.workbench.TaskModels.TaskView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开放 API · 待打标清单（task-todos）：按对象聚合、驳回返工、skipped 排除、可选标签。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_task_todo_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_task_todo_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class OpenTaskTodosIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private TaskView createTask(String title, boolean reviewRequired,
                                List<String> tagCodes, List<String> objectIds) {
        return createTask(title, "driver", reviewRequired, tagCodes, objectIds);
    }

    private TaskView createTask(String title, String objectType, boolean reviewRequired,
                                List<String> tagCodes, List<String> objectIds) {
        return taskService.create(new CreateRequest(TENANT, title, "待打标清单测试", objectType, "normal",
                "pool", null, null, null, reviewRequired, null, tagCodes, objectIds, false, true));
    }

    private void writeTag(long taskId, String objectId, String tagCode, String value) {
        taskService.applyExternalWrite(TENANT, taskId, List.of(
                new TaskWriteLinkHook.Item("driver", objectId, tagCode, value, null, "高", null)));
    }

    private OpenTaskTodoPage todos(Long taskId, String objectType, String objectId,
                                  boolean includeOptional) {
        return taskService.openTaskTodos(TENANT, taskId, objectType, objectId, includeOptional, 1, 50);
    }

    @Test
    void allObjectsTaskAppearsForAnyObjectOfSameType() {
        // 全部对象模式：不指定对象清单，创建即发布
        TaskView task = taskService.create(new CreateRequest(TENANT, "全部对象待办", "验证全对象语义", "driver",
                "normal", "pool", null, null, null, false, null,
                List.of("risk_level", "alarm_count_30d"), List.of(), true, true));
        long taskId = task.task().id();

        // 从未写入、也不在任何快照里的对象：仍应出现在待打标清单，且全部必填标签待打标
        OpenTaskTodoPage page = todos(taskId, "driver", "DRV_NEVER_WRITTEN_1", false);
        assertThat(page.total()).isEqualTo(1);
        OpenTaskTodoView row = page.records().get(0);
        assertThat(row.taskId()).isEqualTo(taskId);
        assertThat(row.objectId()).isEqualTo("DRV_NEVER_WRITTEN_1");
        assertThat(row.pendingTags()).extracting(TaskModels.OpenTodoTag::tagCode)
                .containsExactly("risk_level", "alarm_count_30d");

        // 类型不匹配不返回（不带 task_id 做跨任务查询，避免命中「类型必须与任务一致」的既有校验）
        OpenTaskTodoPage wrongType = todos(null, "vehicle", "DRV_NEVER_WRITTEN_1", false);
        assertThat(wrongType.records()).extracting(OpenTaskTodoView::taskId).doesNotContain(taskId);

        // 写入后转为真实任务项：虚拟项让位，不重复计数
        writeTag(taskId, "DRV_NEVER_WRITTEN_1", "risk_level", "high");
        OpenTaskTodoPage after = todos(taskId, "driver", "DRV_NEVER_WRITTEN_1", false);
        assertThat(after.total()).isEqualTo(1);
        assertThat(after.records().get(0).doneTags()).isEqualTo(1);
        assertThat(after.records().get(0).pendingTags())
                .extracting(TaskModels.OpenTodoTag::tagCode).containsExactly("alarm_count_30d");
    }

    @Test
    void aggregatesPendingTagsByObject() {
        TaskView task = createTask("待办聚合", false,
                List.of("risk_level", "alarm_count_30d"), List.of("DRV96001", "DRV96002"));
        long taskId = task.task().id();

        OpenTaskTodoPage before = todos(taskId, "driver", null, false);
        assertThat(before.total()).isEqualTo(2);
        assertThat(before.records()).allSatisfy(item -> {
            assertThat(item.totalTags()).isEqualTo(2);
            assertThat(item.doneTags()).isZero();
            assertThat(item.pendingTags()).hasSize(2);
        });

        writeTag(taskId, "DRV96001", "risk_level", "high");

        OpenTaskTodoPage after = todos(taskId, "driver", null, false);
        assertThat(after.total()).isEqualTo(2);
        OpenTaskTodoView first = after.records().stream()
                .filter(r -> "DRV96001".equals(r.objectId())).findFirst().orElseThrow();
        assertThat(first.doneTags()).isEqualTo(1);
        assertThat(first.pendingTags()).extracting(TaskModels.OpenTodoTag::tagCode)
                .containsExactly("alarm_count_30d");
        OpenTaskTodoView second = after.records().stream()
                .filter(r -> "DRV96002".equals(r.objectId())).findFirst().orElseThrow();
        TaskModels.OpenTodoTag riskLevel = second.pendingTags().stream()
                .filter(t -> "risk_level".equals(t.tagCode())).findFirst().orElseThrow();
        assertThat(riskLevel.options()).containsExactly("low", "medium", "high");

        OpenTaskTodoPage one = todos(taskId, "driver", "DRV96002", false);
        assertThat(one.total()).isEqualTo(1);
        assertThat(one.records().get(0).objectId()).isEqualTo("DRV96002");
    }

    @Test
    void skippedObjectIsExcluded() {
        TaskView task = createTask("待办跳过", false, List.of("risk_level"),
                List.of("DRV96003", "DRV96004"));
        long taskId = task.task().id();

        taskService.openProgress(TENANT, taskId,
                new ExternalProgressRequest(List.of(new ExternalProgressItem("DRV96003", "skipped", "不适用"))),
                "tester");

        OpenTaskTodoPage page = todos(taskId, "driver", null, false);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records()).extracting(OpenTaskTodoView::objectId).containsExactly("DRV96004");
    }

    @Test
    void rejectedValueReturnsToPending() {
        TaskView task = createTask("待办驳回", true, List.of("risk_level"), List.of("DRV96005"));
        long taskId = task.task().id();

        writeTag(taskId, "DRV96005", "risk_level", "low");
        assertThat(todos(taskId, "driver", null, false).total()).isZero();

        long itemId = taskService.openItems(TENANT, taskId, null, 1, 50)
                .records().get(0).item().id();
        taskService.review(taskId, itemId, new ReviewRequest(false, "信息不实"));

        OpenTaskTodoPage page = todos(taskId, "driver", null, false);
        assertThat(page.total()).isEqualTo(1);
        TaskModels.OpenTodoTag pending = page.records().get(0).pendingTags().get(0);
        assertThat(pending.tagCode()).isEqualTo("risk_level");
        assertThat(pending.lastStatus()).isEqualTo("rejected");
        assertThat(pending.lastValue()).isEqualTo("low");
    }

    @Test
    void optionalTagsOnlyWhenRequested() {
        TaskView task = createTask("待办可选", false, List.of("risk_level"), List.of("DRV96006"));
        long taskId = task.task().id();
        jdbcTemplate.update("UPDATE tag_task_tag SET required = 0 WHERE task_id = ?", taskId);

        assertThat(todos(taskId, "driver", null, false).total()).isZero();

        OpenTaskTodoPage page = todos(taskId, "driver", null, true);
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.records().get(0).pendingTags()).hasSize(1);
        assertThat(page.records().get(0).pendingTags().get(0).required()).isFalse();
    }

    @Test
    void cancelledTaskIsExcluded() {
        TaskView task = createTask("待办取消", false, List.of("risk_level"), List.of("DRV96008"));
        taskService.cancel(task.task().id());

        assertThat(todos(task.task().id(), "driver", null, false).total()).isZero();
        assertThat(todos(null, "driver", null, false).records())
                .noneMatch(r -> r.taskId() == task.task().id());
    }

    @Test
    void multipleObjectsOfSameType() {
        TaskView task = createTask("待办多对象", false, List.of("risk_level"),
                List.of("DRV96101", "DRV96102", "DRV96103"));
        long taskId = task.task().id();

        OpenTaskTodoPage page = todos(taskId, "driver", "DRV96101, DRV96103", false);
        assertThat(page.total()).isEqualTo(2);
        assertThat(page.records()).extracting(OpenTaskTodoView::objectId)
                .containsExactly("DRV96101", "DRV96103");

        // 重复 ID 去重，不重复计数
        assertThat(todos(taskId, "driver", "DRV96101,DRV96101", false).total()).isEqualTo(1);
    }

    @Test
    void mixedTypesPairedByPosition() {
        createTask("待办混合类型-司机", false, List.of("risk_level"), List.of("DRV96201"));
        createTask("待办混合类型-车辆", "vehicle", false, List.of("plate_blacklist"), List.of("苏A88801"));

        OpenTaskTodoPage page = todos(null, "driver,vehicle", "DRV96201,苏A88801", false);
        assertThat(page.records())
                .anyMatch(r -> "driver".equals(r.objectType()) && "DRV96201".equals(r.objectId()))
                .anyMatch(r -> "vehicle".equals(r.objectType()) && "苏A88801".equals(r.objectId()))
                // 位置配对而非笛卡尔积：不存在 (vehicle, DRV96201) / (driver, 苏A88801)
                .noneMatch(r -> "vehicle".equals(r.objectType()) && "DRV96201".equals(r.objectId()))
                .noneMatch(r -> "driver".equals(r.objectType()) && "苏A88801".equals(r.objectId()));

        // 两个类型都指向同一个 ID 时，只命中该 ID 真实归属的类型
        OpenTaskTodoPage paired = todos(null, "driver,vehicle", "DRV96201,DRV96201", false);
        assertThat(paired.records())
                .anyMatch(r -> "driver".equals(r.objectType()) && "DRV96201".equals(r.objectId()))
                .noneMatch(r -> "vehicle".equals(r.objectType()) && "DRV96201".equals(r.objectId()));
    }

    @Test
    void typeAndIdCountMustMatch() {
        createTask("待办配对校验", false, List.of("risk_level"), List.of("DRV96301"));

        assertThatThrownBy(() -> todos(null, "driver,vehicle", "DRV96301", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("一一对应");

        TaskView task = createTask("待办跨类型校验", false, List.of("risk_level"), List.of("DRV96302"));
        assertThatThrownBy(() -> todos(task.task().id(), "driver,vehicle", "DRV96302,苏A88801", false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对象类型");
    }

    @Test
    void objectTypeMustMatchTask() {
        TaskView task = createTask("待办类型校验", false, List.of("risk_level"), List.of("DRV96007"));
        long taskId = task.task().id();

        assertThatThrownBy(() -> todos(taskId, "vehicle", null, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("对象类型");

        assertThatThrownBy(() -> todos(null, null, null, false))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("object_type");
    }
}
