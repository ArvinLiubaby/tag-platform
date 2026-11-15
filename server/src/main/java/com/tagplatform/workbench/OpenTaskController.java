package com.tagplatform.workbench;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 开放 API · 打标任务：本租户任务查询、对象项分页与外部进度回传。
 * 认证与租户由 OpenApiAuthFilter + 签名凭证注入 RequestContext。
 */
@RestController
@RequestMapping({"/api/v1/open", "/open/v1"})
public class OpenTaskController {

    private final TaskService taskService;

    public OpenTaskController(TaskService taskService) {
        this.taskService = taskService;
    }

    @GetMapping("/tasks")
    public ApiResponse<TaskModels.OpenTaskPage> list(
            @RequestParam(value = "task_id", required = false) Long taskId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "operator_id", required = false) String operatorId,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.openList(RequestContext.getTenantId(), status,
                operatorId, objectType, objectId, taskId, page, size));
    }

    @GetMapping("/task-items")
    public ApiResponse<TaskModels.OpenTaskItemPage> taskItems(
            @RequestParam(value = "task_id", required = false) Long taskId,
            @RequestParam(value = "operator_id", required = false) String operatorId,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size,
            @RequestParam(value = "include_values", defaultValue = "false") boolean includeValues) {
        return ApiResponse.ok(taskService.openTaskItems(RequestContext.getTenantId(), taskId,
                operatorId, objectId, status, page, size, includeValues));
    }

    /**
     * 待打标清单：task_id 可选，按对象聚合返回「还缺哪些标签」。
     * object_type / object_id 均支持逗号分隔多值：单类型多对象（driver & 123,324）或多类型按位置配对（driver,vehicle & 123,345）。
     */
    @GetMapping("/task-todos")
    public ApiResponse<TaskModels.OpenTaskTodoPage> taskTodos(
            @RequestParam(value = "task_id", required = false) Long taskId,
            @RequestParam(value = "object_type", required = false) String objectType,
            @RequestParam(value = "object_id", required = false) String objectId,
            @RequestParam(value = "include_optional", defaultValue = "false") boolean includeOptional,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ApiResponse.ok(taskService.openTaskTodos(RequestContext.getTenantId(), taskId,
                objectType, objectId, includeOptional, page, size));
    }

    @GetMapping("/tasks/{taskId}")
    public ApiResponse<TaskModels.TaskView> detail(@PathVariable("taskId") long taskId) {
        return ApiResponse.ok(taskService.openDetail(RequestContext.getTenantId(), taskId));
    }

    @GetMapping("/tasks/{taskId}/items")
    public ApiResponse<TaskModels.OpenItemPage> items(
            @PathVariable("taskId") long taskId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "50") int size) {
        return ApiResponse.ok(taskService.openItems(RequestContext.getTenantId(), taskId, status, page, size));
    }

    @PostMapping("/tasks/{taskId}/progress")
    public ApiResponse<TaskModels.ExternalProgressResult> progress(
            @PathVariable("taskId") long taskId,
            @RequestBody TaskModels.ExternalProgressRequest request) {
        return ApiResponse.ok(taskService.openProgress(RequestContext.getTenantId(), taskId, request,
                RequestContext.getAppKey()));
    }
}
