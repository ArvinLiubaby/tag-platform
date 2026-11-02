package com.tagplatform.workbench;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.model.ConsoleUser;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.workbench.TaskModels.AssignRequest;
import com.tagplatform.workbench.TaskModels.CreateRequest;
import com.tagplatform.workbench.TaskModels.ImportRequest;
import com.tagplatform.workbench.TaskModels.ItemRow;
import com.tagplatform.workbench.TaskModels.ItemValue;
import com.tagplatform.workbench.TaskModels.ReviewRequest;
import com.tagplatform.workbench.TaskModels.TaskDetail;
import com.tagplatform.workbench.TaskModels.TaskEvent;
import com.tagplatform.workbench.TaskModels.TaskItemContext;
import com.tagplatform.workbench.TaskModels.TaskItemView;
import com.tagplatform.workbench.TaskModels.TaskMonitor;
import com.tagplatform.workbench.TaskModels.TaskRow;
import com.tagplatform.workbench.TaskModels.TaskStats;
import com.tagplatform.workbench.TaskModels.TaskTag;
import com.tagplatform.workbench.TaskModels.TaskUpdateRequest;
import com.tagplatform.workbench.TaskModels.TaskView;
import com.tagplatform.write.service.TagValueWriteService;
import com.tagplatform.write.web.dto.OperatorDto;
import com.tagplatform.write.web.dto.WriteRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * 打标任务：创建、对象导入、发布、派发、流转、审核与监控。
 * 任务数据在元数据库；审核通过后复用 TagValueWriteService 写入 history + Outbox。
 */
@Service
public class TaskService {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final Set<String> PRIORITIES = Set.of("low", "normal", "high", "urgent");
    private static final Set<String> ASSIGNMENT_MODES = Set.of("assignee", "team", "pool");
    private static final int MAX_TASK_ITEMS = 10_000;

    private final TaskRepository taskRepository;
    private final TagMetaRepository tagMetaRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ObjectIndexRepository objectIndexRepository;
    private final ConsoleUserRepository userRepository;
    private final TagValueWriteService writeService;
    private final AuditLogService auditLogService;

    public TaskService(TaskRepository taskRepository, TagMetaRepository tagMetaRepository,
                       ObjectTypeRepository objectTypeRepository, ObjectIndexRepository objectIndexRepository,
                       ConsoleUserRepository userRepository, TagValueWriteService writeService,
                       AuditLogService auditLogService) {
        this.taskRepository = taskRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.objectTypeRepository = objectTypeRepository;
        this.objectIndexRepository = objectIndexRepository;
        this.userRepository = userRepository;
        this.writeService = writeService;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------ create

    @Transactional
    public TaskView create(CreateRequest request) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.resolveTenant(request.tenantId());
        ObjectTypeMeta objectType = requireObjectType(request.objectType());
        List<TagMeta> tags = requireTaskTags(request.tagCodes(), tenantId, objectType.objectType());
        String title = requireText(request.title(), "title", 128);
        String description = trimToNull(request.description());
        if (description != null && description.length() > 1024) {
            throw new BizException(ErrorCode.PARAM_INVALID, "description 超长（上限 1024）");
        }
        String priority = normalizePriority(request.priority());
        String assignmentMode = normalizeAssignmentMode(request.assignmentMode());
        Long assigneeUserId = request.assigneeUserId();
        String team = trimToNull(request.team());
        ConsoleUser assignee = null;
        if ("assignee".equals(assignmentMode)) {
            if (assigneeUserId == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "指定人派发必须填写 assignee_user_id");
            }
            assignee = requireAssignableUser(assigneeUserId, tenantId);
        } else if ("team".equals(assignmentMode)) {
            if (team == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "班组派发必须填写 team");
            }
            assigneeUserId = null;
        } else {
            assigneeUserId = null;
            team = null;
        }

        boolean sensitive = tags.stream().anyMatch(TagMeta::needValueAudit);
        boolean reviewRequired = request.reviewRequired() || sensitive;
        ConsoleUser creator = requireCurrentUser();
        Long reviewerUserId = request.reviewerUserId();
        if (reviewerUserId != null) {
            requireReviewerUser(reviewerUserId, tenantId);
        } else if (reviewRequired) {
            reviewerUserId = creator.id();
        }

        List<String> objectIds;
        if (request.allObjects()) {
            objectIds = objectIndexRepository.listIds(tenantId, objectType.objectType(), MAX_TASK_ITEMS + 1);
            if (objectIds.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "对象索引中暂无 " + objectType.objectType() + " 对象；请先选择具体对象或通过历史任务导入");
            }
            if (objectIds.size() > MAX_TASK_ITEMS) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "对象索引中对象数超过单任务上限 " + MAX_TASK_ITEMS + "，请改用指定对象分批创建");
            }
        } else {
            objectIds = normalizeObjectIds(request.objectIds(), objectType, "对象清单");
        }
        LocalDateTime dueAt = request.dueAt();

        String taskCode = nextTaskCode();
        long taskId = taskRepository.insertTask(taskCode, tenantId, title, description,
                objectType.objectType(), "standard", "draft", priority, assignmentMode,
                assigneeUserId, team, reviewerUserId, reviewRequired, objectIds.size(),
                creator.username(), null);
        taskRepository.insertTaskTags(taskId, tags.stream().map(TagMeta::tagCode).toList());
        if (!objectIds.isEmpty()) {
            taskRepository.insertItems(taskId, objectType.objectType(), objectIds);
            objectIds.forEach(id -> objectIndexRepository.touch(tenantId, objectType.objectType(), id));
        }
        if (dueAt != null) {
            taskRepository.updateDraft(taskId, title, description, priority, dueAt);
        }
        taskRepository.insertEvent(taskId, null, "create", null, "draft", WorkbenchAccess.displayName(),
                "创建任务，对象 " + objectIds.size() + " 个，标签 " + tags.size() + " 个");
        if (request.publish()) {
            doPublish(taskId, tenantId, objectType.objectType());
        }
        auditLogService.record("TASK_CREATE", tenantId, objectType.objectType(), null, null,
                "创建打标任务 " + taskCode + "（" + title + "）", "success");
        return view(taskId);
    }

    // ------------------------------------------------------------------ import/publish

    @Transactional
    public TaskView importItems(long taskId, ImportRequest request) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if (!"draft".equals(task.status()) && !"published".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅草稿/已发布任务可导入对象");
        }
        ObjectTypeMeta objectType = requireObjectType(task.objectType());
        List<String> candidates = new ArrayList<>();
        if (request.objectIds() != null) {
            candidates.addAll(request.objectIds());
        }
        candidates.addAll(parseCsv(request.csv()));
        List<String> objectIds = normalizeObjectIds(candidates, objectType, "导入对象");
        Set<String> existing = taskRepository.existingObjectIds(taskId);
        List<String> fresh = objectIds.stream().filter(id -> !existing.contains(id)).toList();
        if (existing.size() + fresh.size() > MAX_TASK_ITEMS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单任务对象数不得超过 " + MAX_TASK_ITEMS);
        }
        if (!fresh.isEmpty()) {
            taskRepository.insertItems(taskId, objectType.objectType(), fresh);
            fresh.forEach(id -> objectIndexRepository.touch(tenantId, objectType.objectType(), id));
        }
        taskRepository.insertEvent(taskId, null, "import", task.status(), task.status(),
                WorkbenchAccess.displayName(), "导入对象 " + fresh.size() + " 个（重复 " + (objectIds.size() - fresh.size()) + " 个已忽略）");
        taskRepository.updateStats(taskId);
        auditLogService.record("TASK_IMPORT", tenantId, objectType.objectType(), null, null,
                "任务 " + task.taskCode() + " 导入对象 " + fresh.size() + " 个", "success");
        return view(taskId);
    }

    @Transactional
    public TaskView publish(long taskId) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        doPublish(taskId, tenantId, task.objectType());
        return view(taskId);
    }

    private void doPublish(long taskId, String tenantId, String objectType) {
        TaskRow task = taskRepository.findTask(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务不存在"));
        if (!"draft".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅草稿任务可发布");
        }
        List<TaskTag> tags = taskRepository.listTaskTags(taskId);
        if (tags.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务未配置目标标签");
        }
        requireTaskTags(tags.stream().map(TaskTag::tagCode).toList(), tenantId, objectType);
        long total = taskRepository.countItems(taskId, null);
        if (total == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务没有对象，无法发布");
        }
        taskRepository.markPublished(taskId);
        if ("assignee".equals(task.assignmentMode()) && task.assigneeUserId() != null) {
            taskRepository.assignPendingItems(taskId, task.assigneeUserId());
        }
        taskRepository.updateStats(taskId);
        taskRepository.insertEvent(taskId, null, "publish", "draft", "published",
                WorkbenchAccess.displayName(), "发布任务，共 " + total + " 个对象");
        auditLogService.record("TASK_PUBLISH", tenantId, objectType, null, null,
                "发布打标任务 " + task.taskCode(), "success");
    }

    @Transactional
    public TaskView updateDraft(long taskId, TaskUpdateRequest request) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if (!"draft".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅草稿任务可编辑");
        }
        String title = requireText(request.title(), "title", 128);
        String description = trimToNull(request.description());
        String priority = normalizePriority(request.priority());
        taskRepository.updateDraft(taskId, title, description, priority, request.dueAt());
        taskRepository.insertEvent(taskId, null, "update", "draft", "draft",
                WorkbenchAccess.displayName(), "编辑任务基本信息");
        return view(taskId);
    }

    // ------------------------------------------------------------------ assign/cancel

    @Transactional
    public TaskView assign(long taskId, AssignRequest request) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if ("completed".equals(task.status()) || "cancelled".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已完成/已取消任务不能改派");
        }
        Long assigneeUserId = request.assigneeUserId();
        String team = trimToNull(request.team());
        String mode = assigneeUserId != null ? "assignee" : team != null ? "team" : "pool";
        if (assigneeUserId != null) {
            requireAssignableUser(assigneeUserId, tenantId);
            taskRepository.assignPendingItems(taskId, assigneeUserId);
        } else {
            taskRepository.clearPendingAssignees(taskId);
        }
        taskRepository.updateAssignee(taskId, assigneeUserId, team, mode);
        taskRepository.insertEvent(taskId, null, "assign", task.status(), task.status(),
                WorkbenchAccess.displayName(), "改派任务（assignee=" + assigneeUserId + ", team=" + team + "）");
        auditLogService.record("TASK_ASSIGN", tenantId, task.objectType(), null, null,
                "改派任务 " + task.taskCode(), "success");
        return view(taskId);
    }

    @Transactional
    public TaskView cancel(long taskId) {
        WorkbenchAccess.requireTaskManager();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if ("completed".equals(task.status()) || "cancelled".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务已完成或已取消");
        }
        long reviewer = currentUserId();
        for (ItemRow item : taskRepository.listItems(taskId)) {
            if (Set.of("pending", "claimed", "in_progress", "rejected", "submitted").contains(item.status())) {
                taskRepository.markItemSkipped(item.id(), reviewer, "任务取消");
                taskRepository.insertEvent(taskId, item.id(), "skip", item.status(), "skipped",
                        WorkbenchAccess.displayName(), "任务取消，保留已通过值");
            }
        }
        taskRepository.updateTaskStatus(taskId, "cancelled");
        taskRepository.insertEvent(taskId, null, "cancel", task.status(), "cancelled",
                WorkbenchAccess.displayName(), "取消任务");
        taskRepository.updateStats(taskId);
        auditLogService.record("TASK_CANCEL", tenantId, task.objectType(), null, null,
                "取消打标任务 " + task.taskCode(), "success");
        return view(taskId);
    }

    // ------------------------------------------------------------------ query

    public List<TaskView> list(String tenantId, String status, boolean mine, int limit, int offset) {
        Long userId = mine ? currentUserId() : null;
        return taskRepository.listTasks(tenantId, status, userId, mine, limit, offset).stream()
                .map(row -> new TaskView(row, taskRepository.listTaskTags(row.id()),
                        taskRepository.stats(row.id())))
                .toList();
    }

    // ------------------------------------------------------------------ open api

    /** 开放 API：本租户任务分页 */
    public TaskModels.OpenTaskPage openList(String tenantId, String status, int page, int size) {
        requireTenantContext(tenantId);
        int sizeNorm = Math.min(Math.max(size, 1), 100);
        int pageNorm = Math.max(page, 1);
        List<TaskView> views = taskRepository.listTasks(tenantId, status, null, false, sizeNorm,
                        (pageNorm - 1) * sizeNorm).stream()
                .map(row -> new TaskView(row, taskRepository.listTaskTags(row.id()), taskRepository.stats(row.id())))
                .toList();
        return new TaskModels.OpenTaskPage(pageNorm, sizeNorm, taskRepository.countTasks(tenantId, status), views);
    }

    /** 开放 API：任务详情（标签 + 进度统计） */
    public TaskView openDetail(String tenantId, long taskId) {
        requireTenantContext(tenantId);
        TaskRow row = requireTask(taskId, tenantId);
        return new TaskView(row, taskRepository.listTaskTags(taskId), taskRepository.stats(taskId));
    }

    /** 开放 API：任务对象项分页（含已写入的标签值） */
    public TaskModels.OpenItemPage openItems(String tenantId, long taskId, String status, int page, int size) {
        requireTenantContext(tenantId);
        requireTask(taskId, tenantId);
        int sizeNorm = Math.min(Math.max(size, 1), 500);
        int pageNorm = Math.max(page, 1);
        List<TaskModels.ItemRow> rows = taskRepository.listItems(taskId, status, sizeNorm, (pageNorm - 1) * sizeNorm);
        Map<Long, List<ItemValue>> values = taskRepository.valuesByTask(taskId);
        List<TaskItemView> views = rows.stream()
                .map(row -> new TaskItemView(row, values.getOrDefault(row.id(), List.of())))
                .toList();
        return new TaskModels.OpenItemPage(pageNorm, sizeNorm, taskRepository.countItems(taskId, status),
                taskRepository.stats(taskId), views);
    }

    /**
     * 开放 API：外部系统回传任务进度。
     * completed → 任务需审核则 submitted，否则 approved；skipped → skipped。
     * 幂等：已 submitted/approved/skipped 的对象计入 unchanged。
     */
    @Transactional
    public TaskModels.ExternalProgressResult openProgress(String tenantId, long taskId,
                                                          TaskModels.ExternalProgressRequest request,
                                                          String operator) {
        requireTenantContext(tenantId);
        TaskRow task = requireTask(taskId, tenantId);
        if (!Set.of("published", "in_progress", "reviewing").contains(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务当前状态不支持进度回传: " + task.status());
        }
        if (request == null || request.items() == null || request.items().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "items 不能为空");
        }
        if (request.items().size() > 100) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单次最多回传 100 个对象");
        }
        String op = operator == null || operator.isBlank() ? "open-api" : operator;
        int updated = 0;
        int unchanged = 0;
        List<String> notFound = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (TaskModels.ExternalProgressItem item : request.items()) {
            if (item == null || item.objectId() == null || item.objectId().isBlank()) {
                throw new BizException(ErrorCode.PARAM_INVALID, "items[].object_id 不能为空");
            }
            String status = item.status() == null ? "" : item.status().trim().toLowerCase(Locale.ROOT);
            if (!"completed".equals(status) && !"skipped".equals(status)) {
                throw new BizException(ErrorCode.PARAM_INVALID, "items[].status 仅支持 completed / skipped");
            }
            String objectId = item.objectId().trim();
            if (!seen.add(objectId)) {
                continue;
            }
            Optional<TaskModels.ItemRow> found = taskRepository.findItemByObject(taskId, objectId);
            if (found.isEmpty()) {
                notFound.add(objectId);
                continue;
            }
            TaskModels.ItemRow row = found.get();
            if (Set.of("submitted", "approved", "skipped").contains(row.status())) {
                unchanged++;
                continue;
            }
            String target = "skipped".equals(status) ? "skipped"
                    : (task.reviewRequired() ? "submitted" : "approved");
            taskRepository.markItemExternal(row.id(), target, op, op, null, item.remark());
            taskRepository.insertEvent(taskId, row.id(), "external_progress", row.status(), target, op,
                    item.remark() == null || item.remark().isBlank() ? "开放 API 回传进度" : item.remark());
            updated++;
        }
        refreshTaskStatus(taskId);
        return new TaskModels.ExternalProgressResult(taskId, updated, unchanged, notFound.size(),
                taskRepository.stats(taskId), notFound);
    }

    private void requireTenantContext(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new BizException(ErrorCode.CONSOLE_UNAUTHORIZED, "缺少租户上下文（请使用有效的 AppKey 签名请求）");
        }
    }

    public TaskDetail detail(long taskId) {
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        List<TaskItemView> items = itemViews(taskId, null, 0, 200);
        return new TaskDetail(task, taskRepository.listTaskTags(taskId), items,
                taskRepository.stats(taskId), taskRepository.listEvents(taskId, 100));
    }

    public long itemTotal(long taskId, String status) {
        return taskRepository.countItems(taskId, status);
    }

    public List<TaskItemView> itemViews(long taskId, String status, int page, int size) {
        String tenantId = WorkbenchAccess.requireTenant();
        requireTask(taskId, tenantId);
        int normalizedSize = Math.min(Math.max(size, 1), 500);
        int offset = Math.max(page, 0) * normalizedSize;
        List<ItemRow> rows = taskRepository.listItems(taskId, status, normalizedSize, offset);
        Map<Long, List<ItemValue>> values = taskRepository.valuesByTask(taskId);
        return rows.stream().map(row -> new TaskItemView(row, values.getOrDefault(row.id(), List.of()))).toList();
    }

    public TaskMonitor monitor(String tenantId) {
        return taskRepository.monitor(tenantId);
    }

    public TaskView view(long taskId) {
        TaskRow task = taskRepository.findTask(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务不存在: " + taskId));
        return new TaskView(task, taskRepository.listTaskTags(taskId), taskRepository.stats(taskId));
    }

    public TaskItemView itemView(long itemId) {
        String tenantId = WorkbenchAccess.requireTenant();
        ItemRow item = taskRepository.findItem(itemId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务项不存在: " + itemId));
        TaskRow task = requireTask(item.taskId(), tenantId);
        return new TaskItemView(item, taskRepository.listItemValues(itemId));
    }

    public TaskModels.TaskItemContext itemContext(long itemId) {
        String tenantId = WorkbenchAccess.requireTenant();
        ItemRow item = taskRepository.findItem(itemId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务项不存在: " + itemId));
        TaskRow task = requireTask(item.taskId(), tenantId);
        return new TaskModels.TaskItemContext(item, task, taskRepository.listTaskTags(task.id()),
                taskRepository.listItemValues(itemId));
    }

    // ------------------------------------------------------------------ review

    @Transactional
    public TaskItemView review(long taskId, long itemId, ReviewRequest request) {
        WorkbenchAccess.requireReviewer();
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if ("cancelled".equals(task.status()) || "completed".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务已结束，不能审核");
        }
        ItemRow item = taskRepository.findItem(itemId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务项不存在: " + itemId));
        if (item.taskId() != taskId) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务项不属于该任务");
        }
        if (!"submitted".equals(item.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "仅已提交项可审核（当前 " + item.status() + "）");
        }
        List<ItemValue> values = taskRepository.listItemValues(itemId);
        if (values.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务项没有提议值");
        }
        long reviewerId = currentUserId();
        if (request.approved()) {
            String bizRequestId = reviewBizId(item, values);
            List<WriteRequest.WriteItem> writeItems = values.stream().map(value -> new WriteRequest.WriteItem(
                    item.objectType(), item.objectId(), value.tagCode(), value.tagValue(),
                    null, null,
                    new OperatorDto(
                            item.operatorId() == null ? task.createdBy() : item.operatorId(),
                            item.operatorName() == null ? task.createdBy() : item.operatorName(),
                            "human",
                            item.operatorOrg()),
                    "adhoc".equals(task.taskType()) ? "workbench" : "workbench_task",
                    value.confidence(), value.remark(), value.evidenceUrl(),
                    "{\"task_id\":" + task.id() + ",\"item_id\":" + item.id() + "}")).toList();
            writeService.writeReviewed(new WriteRequest(bizRequestId, writeItems), tenantId,
                    RequestContext.getRequestId(), WorkbenchAccess.displayName(), request.remark());
            taskRepository.updateValueStatus(itemId, "approved");
            taskRepository.markItemApproved(itemId, reviewerId);
            taskRepository.insertEvent(taskId, itemId, "approve", "submitted", "approved",
                    WorkbenchAccess.displayName(), request.remark());
        } else {
            String remark = trimToNull(request.remark());
            if (remark == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "驳回必须填写原因");
            }
            taskRepository.updateValueStatus(itemId, "rejected");
            taskRepository.markItemRejected(itemId, reviewerId, remark);
            taskRepository.insertEvent(taskId, itemId, "reject", "submitted", "rejected",
                    WorkbenchAccess.displayName(), remark);
        }
        refreshTaskStatus(taskId);
        auditLogService.record(request.approved() ? "TASK_ITEM_APPROVE" : "TASK_ITEM_REJECT",
                tenantId, item.objectType(), item.objectId(), null,
                "任务 " + task.taskCode() + " 项 " + itemId + " " + (request.approved() ? "审核通过" : "驳回"),
                "success");
        return new TaskItemView(taskRepository.findItem(itemId).orElse(item),
                taskRepository.listItemValues(itemId));
    }

    private String reviewBizId(ItemRow item, List<ItemValue> values) {
        long stamp = item.submittedAt() == null ? 0L : item.submittedAt().toEpochSecond(ZoneOffset.UTC);
        String fingerprint = values.stream()
                .map(value -> value.tagCode() + "=" + value.tagValue())
                .collect(java.util.stream.Collectors.joining("|"));
        return "task-review-" + item.id() + "-" + stamp + "-"
                + Integer.toUnsignedString(fingerprint.hashCode(), 36);
    }

    // ------------------------------------------------------------------ ad-hoc review

    /** 临时打标的敏感值：生成只含一个对象的审核任务，等待审核通过后写入 */
    @Transactional
    public TaskItemContext createAdhocReview(String tenantId, String objectType, String objectId,
                                             List<ItemValue> values, ConsoleUser submitter) {
        ObjectTypeMeta type = requireObjectType(objectType);
        if (!type.idMatches(objectId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 格式不符合 " + objectType);
        }
        List<String> tagCodes = values.stream().map(ItemValue::tagCode).toList();
        requireTaskTags(tagCodes, tenantId, objectType);
        String code = nextTaskCode();
        String title = "临时打标审核 · " + objectId;
        long taskId = taskRepository.insertTask(code, tenantId, title, "工作台临时打标的敏感标签审核",
                objectType, "adhoc", "reviewing", "normal", "pool", null, null,
                null, true, 1, submitter.username(),
                LocalDateTime.now());
        taskRepository.insertTaskTags(taskId, tagCodes);
        taskRepository.insertItems(taskId, objectType, List.of(objectId));
        ItemRow item = taskRepository.listItems(taskId).get(0);
        taskRepository.saveItemValues(item.id(), values.stream()
                .map(value -> new ItemValue(value.tagCode(), value.tagValue(), value.remark(),
                        value.evidenceUrl(), value.confidence(), "proposed"))
                .toList());
        taskRepository.markItemSubmitted(item.id(), submitter.operatorId(), submitter.operatorName(),
                submitter.operatorOrg());
        taskRepository.insertEvent(taskId, null, "create", null, "reviewing", submitter.displayName(),
                "临时打标触发敏感标签审核");
        taskRepository.insertEvent(taskId, item.id(), "submit", "pending", "submitted",
                submitter.displayName(), "提交敏感标签值，等待审核");
        taskRepository.updateStats(taskId);
        objectIndexRepository.touch(tenantId, objectType, objectId);
        auditLogService.record("TASK_ADHOC_CREATE", tenantId, objectType, objectId, null,
                "临时打标生成审核任务 " + code, "success");
        return new TaskModels.TaskItemContext(taskRepository.findItem(item.id()).orElse(item),
                taskRepository.findTask(taskId).orElseThrow(), taskRepository.listTaskTags(taskId),
                taskRepository.listItemValues(item.id()));
    }

    // ------------------------------------------------------------------ status

    @Transactional
    public void refreshTaskStatus(long taskId) {
        TaskRow task = taskRepository.findTask(taskId).orElse(null);
        if (task == null) {
            return;
        }
        TaskStats stats = taskRepository.stats(taskId);
        taskRepository.updateStats(taskId);
        if ("draft".equals(task.status()) || "cancelled".equals(task.status())) {
            return;
        }
        long terminal = stats.approved() + stats.skipped();
        if (stats.total() > 0 && terminal >= stats.total()) {
            if (!"completed".equals(task.status())) {
                taskRepository.markCompleted(taskId);
                taskRepository.insertEvent(taskId, null, "complete", task.status(), "completed",
                        "system", "全部任务项处理完成");
                auditLogService.record("TASK_COMPLETE", task.tenantId(), task.objectType(), null, null,
                        "打标任务完成 " + task.taskCode(), "success");
            }
            return;
        }
        boolean awaitingReview = stats.submitted() > 0
                && stats.pending() == 0 && stats.claimed() == 0 && stats.inProgress() == 0
                && stats.rejected() == 0;
        if (awaitingReview) {
            if (!"reviewing".equals(task.status())) {
                taskRepository.updateTaskStatus(taskId, "reviewing");
                taskRepository.insertEvent(taskId, null, "review", task.status(), "reviewing",
                        "system", "全部可处理项已提交，等待审核");
            }
            return;
        }
        long active = stats.claimed() + stats.inProgress() + stats.approved() + stats.rejected()
                + stats.submitted();
        if (active > 0 && !"in_progress".equals(task.status())) {
            taskRepository.updateTaskStatus(taskId, "in_progress");
            taskRepository.insertEvent(taskId, null, "progress", task.status(), "in_progress",
                    "system", "任务开始执行");
        }
    }

    // ------------------------------------------------------------------ helpers

    public TaskRow requireTask(long taskId, String tenantId) {
        TaskRow task = taskRepository.findTask(taskId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务不存在: " + taskId));
        if (!task.tenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "无权访问其他租户任务");
        }
        return task;
    }

    public ObjectTypeMeta requireObjectType(String objectType) {
        if (objectType == null || objectType.isBlank()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "object_type 不能为空");
        }
        return objectTypeRepository.findByType(objectType.trim())
                .filter(ObjectTypeMeta::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                        "对象类型未注册或已停用: " + objectType));
    }

    public List<TagMeta> requireTaskTags(List<String> tagCodes, String tenantId, String objectType) {
        if (tagCodes == null || tagCodes.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "至少选择一个目标标签");
        }
        Set<String> codes = new LinkedHashSet<>();
        for (String code : tagCodes) {
            String trimmed = trimToNull(code);
            if (trimmed == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "tag_code 不能为空");
            }
            codes.add(trimmed);
        }
        Map<String, TagMeta> metas = new LinkedHashMap<>();
        tagMetaRepository.findByCodes(codes).forEach(meta -> metas.put(meta.tagCode(), meta));
        List<TagMeta> result = new ArrayList<>();
        for (String code : codes) {
            TagMeta meta = metas.get(code);
            if (meta == null || !meta.visibleTo(tenantId)) {
                throw new BizException(ErrorCode.TAG_NOT_FOUND, "标签不存在或当前租户不可用: " + code);
            }
            if (!meta.online()) {
                throw new BizException(ErrorCode.TAG_NOT_ONLINE, "标签未上线: " + code);
            }
            if (!meta.writable()) {
                throw new BizException(ErrorCode.TAG_WRITE_DISABLED, "标签不允许写入: " + code);
            }
            if (!meta.supportsObjectType(objectType)) {
                throw new BizException(ErrorCode.TAG_OBJECT_TYPE_MISMATCH,
                        "标签 " + code + " 不适用于对象类型 " + objectType);
            }
            result.add(meta);
        }
        return result;
    }

    public ConsoleUser requireCurrentUser() {
        return userRepository.findByUsername(WorkbenchAccess.currentUsername())
                .orElseThrow(() -> new BizException(ErrorCode.CONSOLE_USER_NOT_FOUND, "当前控制台用户不存在"));
    }

    public long currentUserId() {
        return requireCurrentUser().id();
    }

    private ConsoleUser requireAssignableUser(Long userId, String tenantId) {
        ConsoleUser user = userRepository.findById(userId)
                .filter(ConsoleUser::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "派发对象不存在或已停用: " + userId));
        if (user.tenantId() != null && !user.tenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "不能派发给其他租户用户");
        }
        if (!"TAGGER".equals(user.userRole()) && !"TASK_MANAGER".equals(user.userRole())
                && !"ADMIN".equals(user.userRole())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "派发对象需为 TAGGER / TASK_MANAGER / ADMIN");
        }
        return user;
    }

    private void requireReviewerUser(Long userId, String tenantId) {
        ConsoleUser user = userRepository.findById(userId)
                .filter(ConsoleUser::enabled)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "审核人不存在或已停用: " + userId));
        if (user.tenantId() != null && !user.tenantId().equals(tenantId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "审核人必须属于任务租户");
        }
        if (!"AUDITOR".equals(user.userRole()) && !"TASK_MANAGER".equals(user.userRole())
                && !"TENANT_ADMIN".equals(user.userRole()) && !"ADMIN".equals(user.userRole())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "审核人需为 AUDITOR / TASK_MANAGER / TENANT_ADMIN / ADMIN");
        }
    }

    private List<String> normalizeObjectIds(List<String> rawObjectIds, ObjectTypeMeta type, String field) {
        if (rawObjectIds == null || rawObjectIds.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        List<String> invalid = new ArrayList<>();
        for (String raw : rawObjectIds) {
            String id = trimToNull(raw);
            if (id == null) {
                continue;
            }
            if (!type.idMatches(id)) {
                if (invalid.size() < 5) {
                    invalid.add(id);
                }
            } else {
                ids.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    field + "包含不符合 " + type.objectType() + " 格式的对象: " + String.join(", ", invalid)
                            + "（pattern: " + type.idPattern() + "）");
        }
        if (ids.size() > MAX_TASK_ITEMS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "单任务对象数不得超过 " + MAX_TASK_ITEMS);
        }
        return new ArrayList<>(ids);
    }

    private List<String> parseCsv(String csv) {
        if (csv == null || csv.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String line : csv.split("\\r?\\n")) {
            String value = line.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.contains(",")) {
                value = value.split(",", -1)[0].trim();
            } else if (value.contains("\t")) {
                value = value.split("\t", -1)[0].trim();
            } else if (value.contains(";")) {
                value = value.split(";", -1)[0].trim();
            }
            String lower = value.toLowerCase(Locale.ROOT);
            if ("object_id".equals(lower) || "objectid".equals(lower) || "对象id".equals(lower)
                    || "对象编号".equals(lower)) {
                continue;
            }
            result.add(value);
        }
        return result;
    }

    private String nextTaskCode() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 5).toUpperCase(Locale.ROOT);
        return "TK-" + LocalDate.now().format(DAY) + "-" + suffix;
    }

    private String normalizePriority(String priority) {
        String normalized = priority == null || priority.isBlank() ? "normal" : priority.trim().toLowerCase(Locale.ROOT);
        if (!PRIORITIES.contains(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "priority 仅支持 low/normal/high/urgent");
        }
        return normalized;
    }

    private String normalizeAssignmentMode(String mode) {
        String normalized = mode == null || mode.isBlank() ? "pool" : mode.trim().toLowerCase(Locale.ROOT);
        if (!ASSIGNMENT_MODES.contains(normalized)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "assignment_mode 仅支持 assignee/team/pool");
        }
        return normalized;
    }

    private String requireText(String value, String field, int max) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, field + " 不能为空");
        }
        if (trimmed.length() > max) {
            throw new BizException(ErrorCode.PARAM_INVALID, field + " 超长（上限 " + max + "）");
        }
        return trimmed;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
