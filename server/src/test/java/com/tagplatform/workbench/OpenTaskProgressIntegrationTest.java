package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import com.tagplatform.workbench.TaskModels.CreateRequest;
import com.tagplatform.workbench.TaskModels.ExternalProgressItem;
import com.tagplatform.workbench.TaskModels.ExternalProgressRequest;
import com.tagplatform.workbench.TaskModels.ExternalProgressResult;
import com.tagplatform.workbench.TaskModels.OpenItemPage;
import com.tagplatform.workbench.TaskModels.OpenTaskPage;
import com.tagplatform.workbench.TaskModels.TaskView;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 开放 API 任务能力：任务分页/详情/对象项 + 外部进度回传（幂等、租户隔离、需审核任务）。
 */
@SpringBootTest(properties = {
        "spring.profiles.active=demo",
        "spring.datasource.url=jdbc:h2:mem:tp_open_task_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "tag-platform.starrocks.url=jdbc:h2:mem:tp_open_task_it;MODE=MySQL;DB_CLOSE_DELAY=-1",
})
class OpenTaskProgressIntegrationTest {

    private static final String TENANT = "risk_control";

    @Autowired
    private TaskService taskService;

    @BeforeEach
    void setUp() {
        RequestContext.setConsoleUser("admin", "ADMIN", "平台管理员", TENANT,
                "admin", "平台管理员", "平台", "平台");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private TaskView createTask(String title, boolean reviewRequired, List<String> objectIds) {
        return taskService.create(new CreateRequest(TENANT, title, "开放进度测试", "driver", "normal", "pool",
                null, null, null, reviewRequired, null, List.of("risk_level"), objectIds, false, true));
    }

    @Test
    void listDetailItemsAndIdempotentProgress() {
        TaskView created = createTask("开放任务A", false,
                List.of("DRV98001", "DRV98002", "DRV98003"));
        long taskId = created.task().id();

        OpenTaskPage page = taskService.openList(TENANT, "published", 1, 20);
        assertThat(page.total()).isGreaterThanOrEqualTo(1);
        assertThat(page.records()).anyMatch(v -> v.task().id() == taskId);

        TaskView detail = taskService.openDetail(TENANT, taskId);
        assertThat(detail.tags()).extracting(TaskModels.TaskTag::tagCode).contains("risk_level");

        OpenItemPage items = taskService.openItems(TENANT, taskId, null, 1, 50);
        assertThat(items.total()).isEqualTo(3);
        assertThat(items.records()).allMatch(r -> "pending".equals(r.item().status()));

        ExternalProgressResult first = taskService.openProgress(TENANT, taskId,
                new ExternalProgressRequest(List.of(
                        new ExternalProgressItem("DRV98001", "completed", null),
                        new ExternalProgressItem("DRV98002", "COMPLETED", "外部系统完成"),
                        new ExternalProgressItem("DRV99999", "completed", null))), "AK_IT");
        assertThat(first.updated()).isEqualTo(2);
        assertThat(first.notFound()).isEqualTo(1);
        assertThat(first.stats().approved()).isEqualTo(2);
        assertThat(first.stats().pending()).isEqualTo(1);

        ExternalProgressResult again = taskService.openProgress(TENANT, taskId,
                new ExternalProgressRequest(List.of(
                        new ExternalProgressItem("DRV98001", "completed", null))), "AK_IT");
        assertThat(again.updated()).isZero();
        assertThat(again.unchanged()).isEqualTo(1);

        assertThat(taskService.openItems(TENANT, taskId, "approved", 1, 50).total()).isEqualTo(2);
    }

    @Test
    void reviewRequiredTaskGoesToSubmittedAndTenantIsolation() {
        TaskView created = createTask("开放任务B", true, List.of("DRV98101", "DRV98102"));
        long taskId = created.task().id();

        ExternalProgressResult result = taskService.openProgress(TENANT, taskId,
                new ExternalProgressRequest(List.of(
                        new ExternalProgressItem("DRV98101", "completed", null),
                        new ExternalProgressItem("DRV98102", "skipped", "不适用"))), "AK_IT");
        assertThat(result.stats().submitted()).isEqualTo(1);
        assertThat(result.stats().skipped()).isEqualTo(1);
        assertThat(taskService.openDetail(TENANT, taskId).task().status()).isEqualTo("reviewing");

        assertThatThrownBy(() -> taskService.openDetail("system_a", taskId))
                .isInstanceOf(BizException.class);
        assertThatThrownBy(() -> taskService.openProgress("system_a", taskId,
                new ExternalProgressRequest(List.of(new ExternalProgressItem("DRV98101", "completed", null))),
                "AK_OTHER")).isInstanceOf(BizException.class);
    }
}
