package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.common.TaskWriteLinkHook;
import com.tagplatform.workbench.TaskModels.CreateRequest;
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
 * 全部对象模式（tag_task.all_objects）：
 * 对象索引为空也能创建/发布；外部写入的新对象自动纳入；
 * 指定对象任务仍保持「对象必须在任务中」的校验。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_all_objects_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_all_objects_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class AllObjectsTaskIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private TaskService taskService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
        // 每个用例从「对象索引为空」开始，避免用例间互相影响
        jdbcTemplate.update("DELETE FROM object_index WHERE tenant_id = ? AND object_type = 'vehicle'", TENANT);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private TaskView createVehicleAllObjects(boolean publish) {
        return taskService.create(new CreateRequest(TENANT, "全部对象任务", "验证全对象模式", "vehicle",
                "normal", "pool", null, null, null, false, null,
                List.of("plate_blacklist"), List.of(), true, publish));
    }

    @Test
    void emptyObjectIndexCanCreateAndPublish() {
        TaskView view = createVehicleAllObjects(true);

        assertThat(view.task().allObjects()).isTrue();
        assertThat(view.task().totalItems()).isZero();
        assertThat(view.task().status()).isEqualTo("published");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM object_index WHERE tenant_id=? AND object_type='vehicle'",
                Integer.class, TENANT)).isZero();
    }

    @Test
    void writeWithNewObjectIsAcceptedAndItemCreated() {
        TaskView view = createVehicleAllObjects(true);
        long taskId = view.task().id();
        String vin = "LSVAA12345678901";
        List<TaskWriteLinkHook.Item> items = List.of(
                new TaskWriteLinkHook.Item("vehicle", vin, "plate_blacklist", "true", null, "高", null));

        // 写前校验不应再报「对象不在任务中」
        taskService.validateExternalWrite(TENANT, taskId, items);
        taskService.applyExternalWrite(TENANT, taskId, items);

        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_task_item WHERE task_id=? AND object_id=?",
                Integer.class, taskId, vin)).isEqualTo(1);
        // plate_blacklist 属敏感标签，写齐后进入 submitted 等审核（值本身已 approved）
        assertThat(jdbcTemplate.queryForObject(
                "SELECT status FROM tag_task_item WHERE task_id=? AND object_id=?",
                String.class, taskId, vin)).isEqualTo("submitted");
        assertThat(jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_task_item_value v JOIN tag_task_item i ON i.id = v.item_id
                 WHERE i.task_id = ? AND i.object_id = ? AND v.status = 'approved'
                """, Integer.class, taskId, vin)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT total_items FROM tag_task WHERE id=?", Integer.class, taskId)).isEqualTo(1);
        // 自动纳入的对象同步登记到对象索引，便于后续按对象检索
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM object_index WHERE tenant_id=? AND object_type='vehicle' AND object_id=?",
                Integer.class, TENANT, vin)).isEqualTo(1);
    }

    @Test
    void allObjectsTaskIsMatchedByUnwrittenObjectId() {
        TaskView view = createVehicleAllObjects(true);
        long taskId = view.task().id();
        String neverWritten = "NEVER_WRITTEN_0001";

        // 该对象从未写入、也不在创建时的快照里 —— 「全部对象」任务仍应命中
        TaskModels.OpenTaskPage page = taskService.openList(
                TENANT, null, null, "vehicle", neverWritten, null, 1, 20);
        assertThat(page.records()).extracting(v -> v.task().id()).contains(taskId);
        assertThat(page.total()).isGreaterThanOrEqualTo(1);

        // 只给 object_id、不给类型时同样命中（兼容 v1.21.0 单值语义）
        TaskModels.OpenTaskPage byIdOnly = taskService.openList(
                TENANT, null, null, null, neverWritten, null, 1, 20);
        assertThat(byIdOnly.records()).extracting(v -> v.task().id()).contains(taskId);

        // 类型不匹配时不应命中
        TaskModels.OpenTaskPage wrongType = taskService.openList(
                TENANT, null, null, "driver", neverWritten, null, 1, 20);
        assertThat(wrongType.records()).extracting(v -> v.task().id()).doesNotContain(taskId);
    }

    @Test
    void specifiedObjectTaskIsNotMatchedByUnknownObjectId() {
        TaskView view = taskService.create(new CreateRequest(TENANT, "指定对象任务", null, "driver",
                "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of("DRV97101"), false, true));
        long taskId = view.task().id();

        TaskModels.OpenTaskPage miss = taskService.openList(
                TENANT, null, null, "driver", "DRV_UNKNOWN_9", null, 1, 20);
        assertThat(miss.records()).extracting(v -> v.task().id()).doesNotContain(taskId);

        TaskModels.OpenTaskPage hit = taskService.openList(
                TENANT, null, null, "driver", "DRV97101", null, 1, 20);
        assertThat(hit.records()).extracting(v -> v.task().id()).contains(taskId);
    }

    @Test
    void specifiedObjectTaskStillRejectsUnknownObject() {
        TaskView view = taskService.create(new CreateRequest(TENANT, "指定对象任务", null, "driver",
                "normal", "pool", null, null, null, false, null,
                List.of("risk_level"), List.of("DRV97101"), false, true));
        long taskId = view.task().id();
        assertThat(view.task().allObjects()).isFalse();

        List<TaskWriteLinkHook.Item> items = List.of(
                new TaskWriteLinkHook.Item("driver", "DRV97102", "risk_level", "high", null, "高", null));
        assertThatThrownBy(() -> taskService.validateExternalWrite(TENANT, taskId, items))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("不在任务");

        taskService.applyExternalWrite(TENANT, taskId, items);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_task_item WHERE task_id=?", Integer.class, taskId)).isEqualTo(1);
    }
}
