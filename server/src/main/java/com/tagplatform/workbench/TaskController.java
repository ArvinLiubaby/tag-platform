package com.tagplatform.workbench;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.ConsoleUserService;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.workbench.TaskModels.AssignRequest;
import com.tagplatform.workbench.TaskModels.CreateRequest;
import com.tagplatform.workbench.TaskModels.ImportRequest;
import com.tagplatform.workbench.TaskModels.ItemPage;
import com.tagplatform.workbench.TaskModels.ObjectIndexPage;
import com.tagplatform.workbench.TaskModels.ReviewRequest;
import com.tagplatform.workbench.TaskModels.TaskDetail;
import com.tagplatform.workbench.TaskModels.TaskItemView;
import com.tagplatform.workbench.TaskModels.TaskMonitor;
import com.tagplatform.workbench.TaskModels.TaskUpdateRequest;
import com.tagplatform.workbench.TaskModels.TaskView;
import com.tagplatform.console.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台 API · 打标任务管理：创建/导入/发布/派发/取消/详情/项/审核/监控。
 */
@RestController
@RequirePermission("page.tasks")
@RequestMapping({"/api/v1/console/tasks", "/console/api/tasks"})
public class TaskController {

    private final TaskService taskService;
    private final ConsoleUserService userService;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectIndexRepository objectIndexRepository;

    public TaskController(TaskService taskService, ConsoleUserService userService,
                          ObjectTypeRepository objectTypeRepository,
                          ObjectIndexRepository objectIndexRepository) {
        this.taskService = taskService;
        this.userService = userService;
        this.objectTypeRepository = objectTypeRepository;
        this.objectIndexRepository = objectIndexRepository;
    }

    @GetMapping
    public ApiResponse<List<TaskView>> list(
            @RequestParam(value = "tenantId", required = false) String tenantId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "mine", defaultValue = "false") boolean mine,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        WorkbenchAccess.requireWorkbench();
        String tenant = WorkbenchAccess.resolveTenant(tenantId);
        boolean onlyMine = mine || "TAGGER".equals(RequestContext.getConsoleRole());
        return ApiResponse.ok(taskService.list(tenant, status, onlyMine, limit, offset));
    }

    @RequirePermission("op.task.manage")
    @PostMapping
    public ApiResponse<TaskView> create(@RequestBody CreateRequest request) {
        return ApiResponse.ok(taskService.create(request));
    }

    @GetMapping("/{id}")
    public ApiResponse<TaskDetail> detail(@PathVariable("id") long id) {
        WorkbenchAccess.requireWorkbench();
        return ApiResponse.ok(taskService.detail(id));
    }

    @RequirePermission("op.task.manage")
    @PutMapping("/{id}")
    public ApiResponse<TaskView> update(@PathVariable("id") long id, @RequestBody TaskUpdateRequest request) {
        return ApiResponse.ok(taskService.updateDraft(id, request));
    }

    @RequirePermission("op.task.manage")
    @PostMapping("/{id}/items/import")
    public ApiResponse<TaskView> importItems(@PathVariable("id") long id,
                                             @RequestBody ImportRequest request) {
        return ApiResponse.ok(taskService.importItems(id, request));
    }

    @RequirePermission("op.task.manage")
    @PostMapping("/{id}/publish")
    public ApiResponse<TaskView> publish(@PathVariable("id") long id) {
        return ApiResponse.ok(taskService.publish(id));
    }

    @RequirePermission("op.task.manage")
    @PostMapping("/{id}/assign")
    public ApiResponse<TaskView> assign(@PathVariable("id") long id, @RequestBody AssignRequest request) {
        return ApiResponse.ok(taskService.assign(id, request));
    }

    @RequirePermission("op.task.manage")
    @PostMapping("/{id}/cancel")
    public ApiResponse<TaskView> cancel(@PathVariable("id") long id) {
        return ApiResponse.ok(taskService.cancel(id));
    }

    @GetMapping("/{id}/items")
    public ApiResponse<ItemPage> items(@PathVariable("id") long id,
                                       @RequestParam(value = "status", required = false) String status,
                                       @RequestParam(value = "page", defaultValue = "0") int page,
                                       @RequestParam(value = "size", defaultValue = "50") int size) {
        WorkbenchAccess.requireWorkbench();
        String tenant = WorkbenchAccess.requireTenant();
        taskService.requireTask(id, tenant);
        int normalizedSize = Math.min(Math.max(size, 1), 500);
        long total = taskService.itemTotal(id, status);
        return ApiResponse.ok(new ItemPage(page, normalizedSize, total,
                taskService.itemViews(id, status, page, normalizedSize)));
    }

    @RequirePermission("op.task.review")
    @PostMapping("/{id}/items/{itemId}/review")
    public ApiResponse<TaskItemView> review(@PathVariable("id") long id,
                                            @PathVariable("itemId") long itemId,
                                            @RequestBody ReviewRequest request) {
        return ApiResponse.ok(taskService.review(id, itemId, request));
    }

    @RequirePermission("page.task_monitor")
    @GetMapping("/monitor")
    public ApiResponse<TaskMonitor> monitor(@RequestParam(value = "tenantId", required = false) String tenantId) {
        WorkbenchAccess.requireReviewer();
        return ApiResponse.ok(taskService.monitor(WorkbenchAccess.resolveTenant(tenantId)));
    }

    @RequirePermission("op.task.manage")
    @GetMapping("/assignees")
    public ApiResponse<List<ConsoleUserService.UserView>> assignees(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        WorkbenchAccess.requireTaskManager();
        String tenant = WorkbenchAccess.resolveTenant(tenantId);
        return ApiResponse.ok(userService.listAssignable(tenant));
    }

    /** 对象索引筛选：任务创建时下拉选择对象 */
    @GetMapping("/object-index")
    public ApiResponse<ObjectIndexPage> objectIndex(
            @RequestParam("objectType") String objectType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        WorkbenchAccess.requireWorkbench();
        String tenant = WorkbenchAccess.requireTenant();
        int size = Math.min(Math.max(limit, 1), 200);
        long total = objectIndexRepository.count(tenant, objectType, keyword);
        return ApiResponse.ok(new ObjectIndexPage(total,
                objectIndexRepository.search(tenant, objectType, keyword, size, offset)));
    }

    @GetMapping("/object-types")
    public ApiResponse<List<com.tagplatform.meta.model.ObjectTypeMeta>> objectTypes() {
        WorkbenchAccess.requireWorkbench();
        return ApiResponse.ok(objectTypeRepository.listAll().stream()
                .filter(com.tagplatform.meta.model.ObjectTypeMeta::enabled).toList());
    }
}
