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
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        return ApiResponse.ok(taskService.openList(RequestContext.getTenantId(), status, page, size));
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
