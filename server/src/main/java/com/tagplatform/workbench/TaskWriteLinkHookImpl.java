package com.tagplatform.workbench;

import com.tagplatform.common.TaskWriteLinkHook;
import org.springframework.stereotype.Component;

import java.util.List;

/** 开放写入携带 task_id 时，联动任务排期（校验 + 写后自动推进）。 */
@Component
public class TaskWriteLinkHookImpl implements TaskWriteLinkHook {

    private final TaskService taskService;

    public TaskWriteLinkHookImpl(TaskService taskService) {
        this.taskService = taskService;
    }

    @Override
    public void validate(String tenantId, long taskId, List<Item> items) {
        taskService.validateExternalWrite(tenantId, taskId, items);
    }

    @Override
    public void afterWrite(String tenantId, long taskId, List<Item> items) {
        taskService.applyExternalWrite(tenantId, taskId, items);
    }
}
