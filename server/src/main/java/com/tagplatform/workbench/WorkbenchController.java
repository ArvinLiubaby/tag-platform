package com.tagplatform.workbench;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.workbench.TaskModels.ObjectRow;
import com.tagplatform.workbench.TaskModels.SubmitRequest;
import com.tagplatform.workbench.TaskModels.SubmitResponse;
import com.tagplatform.workbench.TaskModels.TaskItemContext;
import com.tagplatform.workbench.TaskModels.TaskItemView;
import com.tagplatform.workbench.TaskModels.TaskView;
import com.tagplatform.workbench.TaskModels.WorkbenchContext;
import com.tagplatform.workbench.TaskModels.WorkbenchItem;
import com.tagplatform.workbench.TaskModels.WorkbenchProfile;
import com.tagplatform.console.security.RequirePermission;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 控制台 API · 打标工作台：上下文、对象搜索、画像 overlay、动态标签、领取与提交。
 */
@RestController
@RequirePermission("page.workbench")
@RequestMapping({"/api/v1/console/workbench", "/console/api/workbench"})
public class WorkbenchController {

    private final WorkbenchService workbenchService;
    private final ObjectTypeRepository objectTypeRepository;

    public WorkbenchController(WorkbenchService workbenchService, ObjectTypeRepository objectTypeRepository) {
        this.workbenchService = workbenchService;
        this.objectTypeRepository = objectTypeRepository;
    }

    @GetMapping("/context")
    public ApiResponse<WorkbenchContext> context(
            @RequestParam(value = "tenantId", required = false) String tenantId) {
        return ApiResponse.ok(workbenchService.context(tenantId));
    }

    @GetMapping("/objects")
    public ApiResponse<List<ObjectRow>> objects(
            @RequestParam(value = "objectType", required = false) String objectType,
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "limit", defaultValue = "30") int limit) {
        return ApiResponse.ok(workbenchService.objects(objectType, keyword, limit));
    }

    @GetMapping("/objects/profile")
    public ApiResponse<WorkbenchProfile> profile(@RequestParam("objectType") String objectType,
                                                 @RequestParam("objectId") String objectId) {
        return ApiResponse.ok(workbenchService.profile(objectType, objectId));
    }

    @GetMapping("/tags")
    public ApiResponse<List<TagMeta>> tags(
            @RequestParam(value = "objectType", required = false) String objectType) {
        return ApiResponse.ok(workbenchService.tags(objectType));
    }

    @GetMapping("/object-types")
    public ApiResponse<List<ObjectTypeMeta>> objectTypes() {
        WorkbenchAccess.requireWorkbench();
        return ApiResponse.ok(objectTypeRepository.listAll().stream().filter(ObjectTypeMeta::enabled).toList());
    }

    @RequirePermission("op.workbench.submit")
    @PostMapping("/tag-values")
    public ApiResponse<SubmitResponse> submit(@RequestBody SubmitRequest request) {
        return ApiResponse.ok(workbenchService.submit(request));
    }

    @GetMapping("/my-tasks")
    public ApiResponse<List<TaskView>> myTasks(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        return ApiResponse.ok(workbenchService.myTasks(status, limit, offset));
    }

    @GetMapping("/items")
    public ApiResponse<List<WorkbenchItem>> myItems(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "50") int limit) {
        return ApiResponse.ok(workbenchService.myItems(status, limit));
    }

    @GetMapping("/items/{id}")
    public ApiResponse<TaskItemContext> item(@PathVariable("id") long id) {
        return ApiResponse.ok(workbenchService.itemContext(id));
    }

    @RequirePermission("op.workbench.submit")
    @PostMapping("/items/{id}/claim")
    public ApiResponse<TaskItemView> claim(@PathVariable("id") long id) {
        return ApiResponse.ok(workbenchService.claim(id));
    }

    @RequirePermission("op.workbench.submit")
    @PostMapping("/items/{id}/skip")
    public ApiResponse<TaskItemView> skip(@PathVariable("id") long id,
                                          @RequestBody(required = false) SkipBody body) {
        return ApiResponse.ok(workbenchService.skip(id, body == null ? null : body.remark()));
    }

    public record SkipBody(String remark) {
    }
}
