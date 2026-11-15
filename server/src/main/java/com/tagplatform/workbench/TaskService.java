package com.tagplatform.workbench;

import com.tagplatform.audit.AuditLogService;
import com.tagplatform.common.BizException;
import com.tagplatform.common.TaskWriteLinkHook;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.model.ConsoleUser;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.workbench.TaskModels.AcceptRequest;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.meta.TagOptions;
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
import java.util.stream.Collectors;

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
    private final ObjectMapper objectMapper;

    public TaskService(TaskRepository taskRepository, TagMetaRepository tagMetaRepository,
                       ObjectTypeRepository objectTypeRepository, ObjectIndexRepository objectIndexRepository,
                       ConsoleUserRepository userRepository, TagValueWriteService writeService,
                       AuditLogService auditLogService, ObjectMapper objectMapper) {
        this.taskRepository = taskRepository;
        this.tagMetaRepository = tagMetaRepository;
        this.objectTypeRepository = objectTypeRepository;
        this.objectIndexRepository = objectIndexRepository;
        this.userRepository = userRepository;
        this.writeService = writeService;
        this.auditLogService = auditLogService;
        this.objectMapper = objectMapper;
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
        boolean allObjects = request.allObjects();
        if (allObjects) {
            // 全部对象模式：不校验对象索引是否为空，有多少先快照多少；
            // 发布后外部推送的新对象由 applyExternalWrite 自动纳入任务项。
            objectIds = objectIndexRepository.listIds(tenantId, objectType.objectType(), MAX_TASK_ITEMS + 1);
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
                assigneeUserId, team, reviewerUserId, reviewRequired, allObjects, objectIds.size(),
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
                allObjects
                        ? "创建任务（全部对象模式），快照对象 " + objectIds.size() + " 个，标签 " + tags.size() + " 个"
                        : "创建任务，对象 " + objectIds.size() + " 个，标签 " + tags.size() + " 个");
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
        if (total == 0 && !task.allObjects()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务没有对象，无法发布");
        }
        taskRepository.markPublished(taskId);
        if ("assignee".equals(task.assignmentMode()) && task.assigneeUserId() != null) {
            taskRepository.assignPendingItems(taskId, task.assigneeUserId());
        }
        taskRepository.updateStats(taskId);
        taskRepository.insertEvent(taskId, null, "publish", "draft", "published",
                WorkbenchAccess.displayName(),
                total == 0
                        ? "发布任务（全部对象模式，暂无对象，按外部写入动态纳入）"
                        : "发布任务，共 " + total + " 个对象");
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
        if (Set.of("completed", "cancelled", "verified").contains(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "已完成/已取消/已验收任务不能改派");
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
        if (Set.of("completed", "cancelled", "verified").contains(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务已完成、已取消或已验收");
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

    /** 开放 API：本租户任务分页，可按客服（operator_id）/对象过滤，并返回该客服维度的个人进度（旧签名，兼容单值 object_id） */
    public TaskModels.OpenTaskPage openList(String tenantId, String status, String operatorId,
                                            String objectId, int page, int size) {
        return openList(tenantId, status, operatorId, null, objectId, null, page, size);
    }

    /**
     * 开放 API：任务查询。taskId 为空 = 返回租户下全部任务；非空 = 只返回该任务（分页结构不变，total=1）。
     * objectType / objectId 可选，逗号分隔多值、按位置配对（与任务待打标清单一致）；
     * 命中语义 = 任务快照对象项中包含任一所列对象；只传 object_id 时不限定类型（兼容 v1.21.0 单值语义）。
     * status 可选，逗号分隔多值（如 published,in_progress 查「待打标」任务）；单值写法完全兼容。
     */
    public TaskModels.OpenTaskPage openList(String tenantId, String status, String operatorId,
                                            String objectType, String objectId, Long taskId, int page, int size) {
        requireTenantContext(tenantId);
        int sizeNorm = Math.min(Math.max(size, 1), 100);
        int pageNorm = Math.max(page, 1);
        List<String> statuses = parseStatuses(status);
        String op = trimToNull(operatorId);
        TodoScope scope = parseObjectScope(objectType, objectId);
        List<String[]> pairs = scope == null ? List.of() : scope.pairs();
        List<String> types = scope == null ? List.of() : scope.types();
        List<TaskModels.OpenTaskView> views = taskRepository
                .listTasksForOpen(tenantId, statuses, op, types, pairs, taskId, sizeNorm, (pageNorm - 1) * sizeNorm).stream()
                .map(row -> new TaskModels.OpenTaskView(row, taskRepository.listTaskTags(row.id()),
                        taskRepository.stats(row.id()),
                        op == null ? null : itemStatsFor(row.id(), op, pairs)))
                .toList();
        return new TaskModels.OpenTaskPage(pageNorm, sizeNorm,
                taskRepository.countTasksForOpen(tenantId, statuses, op, types, pairs, taskId), views);
    }

    /** status 参数解析：逗号分隔多值 → 去重列表；空返回 null（不过滤）。非法状态值直接 2002（fail-fast） */
    private static final List<String> TASK_STATUSES = List.of(
            "draft", "published", "in_progress", "reviewing", "completed", "verified", "cancelled");

    private List<String> parseStatuses(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        List<String> list = java.util.Arrays.stream(status.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).distinct().toList();
        if (list.isEmpty()) {
            return null;
        }
        for (String s : list) {
            if (!TASK_STATUSES.contains(s)) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "非法任务状态: " + s + "（可选值 " + TASK_STATUSES + "）");
            }
        }
        return list;
    }

    /** 客服维度个人进度：恰好一个对象条件时按该对象收窄统计，否则只按客服统计 */
    private TaskModels.TaskStats itemStatsFor(long taskId, String operatorId, List<String[]> pairs) {
        if (pairs != null && pairs.size() == 1) {
            String[] pair = pairs.get(0);
            return taskRepository.itemStats(taskId, operatorId, pair[0], pair[1]);
        }
        return taskRepository.itemStats(taskId, operatorId, null, null);
    }

    /** 开放 API：跨任务的任务项查询（客服 + 对象维度） */
    public TaskModels.OpenTaskItemPage openTaskItems(String tenantId, Long taskId, String operatorId,
                                                     String objectId, String status, int page, int size,
                                                     boolean includeValues) {
        requireTenantContext(tenantId);
        if (taskId != null) {
            requireTask(taskId, tenantId);
        }
        int sizeNorm = Math.min(Math.max(size, 1), 200);
        int pageNorm = Math.max(page, 1);
        List<TaskModels.OpenTaskItemRow> rows = taskRepository.listOpenTaskItems(tenantId, taskId,
                trimToNull(operatorId), trimToNull(objectId), trimToNull(status), sizeNorm,
                (pageNorm - 1) * sizeNorm);
        List<TaskModels.OpenTaskItemView> views = rows.stream().map(row -> new TaskModels.OpenTaskItemView(
                row.item().taskId(), row.taskCode(), row.taskTitle(), row.item(),
                includeValues ? taskRepository.listItemValues(row.item().id()) : List.of())).toList();
        long total = taskRepository.countOpenTaskItems(tenantId, taskId, trimToNull(operatorId),
                trimToNull(objectId), trimToNull(status));
        List<ItemValue> allValues = views.stream().flatMap(view -> view.values().stream()).toList();
        return new TaskModels.OpenTaskItemPage(pageNorm, sizeNorm, total, views, unitMapOf(allValues));
    }

    /** 任务项/流水的页级单位映射：单位属于标签定义，只下发本页出现且配置了单位的标签 */
    private Map<String, String> unitMapOf(List<ItemValue> values) {
        Set<String> codes = values.stream().map(ItemValue::tagCode)
                .filter(code -> code != null && !code.isBlank())
                .collect(Collectors.toSet());
        if (codes.isEmpty()) {
            return Map.of();
        }
        Map<String, String> units = new LinkedHashMap<>();
        tagMetaRepository.findByCodes(codes).forEach(meta -> {
            if (meta.unit() != null && !meta.unit().isBlank()) {
                units.put(meta.tagCode(), meta.unit());
            }
        });
        return units;
    }

    /**
     * 开放 API：待打标清单（按对象聚合）。
     * taskId 为空 = 租户下所有任务；只返回仍有「必填未写 / 被驳回」标签的对象。
     */
    /** 单次查询的对象类型 / 对象上限，避免超长 URL 与超大 SQL */
    private static final int MAX_TODO_TYPES = 20;
    private static final int MAX_TODO_OBJECTS = 200;

    /**
     * 待办查询的对象范围。
     * pairs 为空表示只按 types 过滤（该类型下全部对象）；否则按 (type, id) 精确配对。
     */
    private record TodoScope(List<String> types, List<String[]> pairs) {
    }

    /** 去重（保持顺序）：重复的对象条件只保留一次，避免重复 OR 条件与重复分页计数 */
    private static List<String[]> dedupePairs(List<String[]> pairs) {
        Map<String, String[]> unique = new LinkedHashMap<>();
        for (String[] pair : pairs) {
            unique.putIfAbsent(pair[0] + "\u0000" + pair[1], pair);
        }
        return new ArrayList<>(unique.values());
    }

    /**
     * 解析任务查询的对象过滤参数（object_type / object_id 均可选，两者都空 = 不按对象过滤，返回 null）。
     * 支持单类型 + 多 ID（driver & 123,324）与多类型按位置配对（driver,vehicle & 123,345）；
     * 只传 object_id 时按对象 ID 匹配、不限定类型（兼容 v1.21.0 的单值 object_id 参数语义，type 置 null）。
     */
    private TodoScope parseObjectScope(String objectType, String objectId) {
        List<String> rawTypes = splitCsv(objectType);
        List<String> rawIds = splitCsv(objectId);
        if (rawTypes.isEmpty() && rawIds.isEmpty()) {
            return null;
        }
        if (rawTypes.isEmpty()) {
            if (rawIds.size() > MAX_TODO_OBJECTS) {
                throw new BizException(ErrorCode.PARAM_INVALID, "object_id 最多 " + MAX_TODO_OBJECTS + " 个");
            }
            List<String[]> idPairs = new ArrayList<>();
            for (String id : rawIds) {
                idPairs.add(new String[]{null, id});
            }
            return new TodoScope(List.of(), dedupePairs(idPairs));
        }
        Set<String> distinctTypes = new LinkedHashSet<>(rawTypes);
        if (distinctTypes.size() > MAX_TODO_TYPES) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_type 最多 " + MAX_TODO_TYPES + " 个");
        }
        if (rawIds.size() > MAX_TODO_OBJECTS) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 最多 " + MAX_TODO_OBJECTS + " 个");
        }
        if (rawIds.isEmpty()) {
            return new TodoScope(new ArrayList<>(distinctTypes), List.of());
        }
        List<String[]> pairs = new ArrayList<>();
        if (rawTypes.size() == 1) {
            // 单类型 + 多对象：driver & 123,324
            for (String id : rawIds) {
                pairs.add(new String[]{rawTypes.get(0), id});
            }
        } else if (rawTypes.size() == rawIds.size()) {
            // 多类型按位置一一配对：driver,vehicle & 123,345
            for (int i = 0; i < rawTypes.size(); i++) {
                pairs.add(new String[]{rawTypes.get(i), rawIds.get(i)});
            }
        } else {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "object_type 与 object_id 需按位置一一对应（个数相同）；只查一种类型时可只传 1 个 object_type 配多个 object_id");
        }
        pairs = dedupePairs(pairs);
        List<String> types = pairs.stream().map(pair -> pair[0]).distinct().toList();
        return new TodoScope(types, pairs);
    }

    /** 待打标清单专用：object_type 必填（其余配对规则与 parseObjectScope 一致） */
    private TodoScope parseTodoScope(String objectType, String objectId) {
        if (splitCsv(objectType).isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING,
                    "object_type 不能为空，支持逗号分隔多值，如 driver,vehicle");
        }
        return parseObjectScope(objectType, objectId);
    }

    private static List<String> splitCsv(String value) {
        String trimmed = trimToNull(value);
        if (trimmed == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : trimmed.split(",")) {
            String item = part.trim();
            if (!item.isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    public TaskModels.OpenTaskTodoPage openTaskTodos(String tenantId, Long taskId, String objectType,
                                                     String objectId, boolean includeOptional,
                                                     int page, int size) {
        requireTenantContext(tenantId);
        TodoScope scope = parseTodoScope(objectType, objectId);
        if (taskId != null) {
            TaskRow task = requireTask(taskId, tenantId);
            for (String type : scope.types()) {
                if (!task.objectType().equals(type)) {
                    throw new BizException(ErrorCode.PARAM_INVALID,
                            "任务对象类型为 " + task.objectType() + "，与查询的 " + type + " 不一致");
                }
            }
        } else {
            for (String type : scope.types()) {
                objectTypeRepository.findByType(type).filter(ObjectTypeMeta::enabled)
                        .orElseThrow(() -> new BizException(ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                                "对象类型未注册或已停用: " + type));
            }
        }

        int sizeNorm = Math.min(Math.max(size, 1), 200);
        int pageNorm = Math.max(page, 1);
        // 指定了对象时把分页放到内存里：需要给「全部对象」任务追加虚拟任务项，SQL 分页会截断
        boolean objectScoped = scope.pairs() != null && !scope.pairs().isEmpty();
        int querySize = objectScoped ? 200 : sizeNorm;
        int queryOffset = objectScoped ? 0 : (pageNorm - 1) * sizeNorm;

        List<TaskModels.OpenTaskItemRow> rows = new ArrayList<>(taskRepository.listOpenTodoItems(
                tenantId, taskId, scope.types(), scope.pairs(), includeOptional,
                querySize, queryOffset));
        long total = taskRepository.countOpenTodoItems(tenantId, taskId, scope.types(), scope.pairs(),
                includeOptional);
        if (objectScoped) {
            List<TaskModels.OpenTaskItemRow> virtuals = allObjectsTodoRows(tenantId, taskId, scope.pairs());
            if (!virtuals.isEmpty()) {
                rows.addAll(virtuals);
                total += virtuals.size();
            }
            rows = rows.stream().skip((long) (pageNorm - 1) * sizeNorm).limit(sizeNorm).toList();
        }

        Map<Long, List<TaskTag>> tagCache = new LinkedHashMap<>();
        Map<Long, Map<Long, List<ItemValue>>> valueCache = new LinkedHashMap<>();
        Map<Long, Map<String, ItemValue>> valueByTag = new LinkedHashMap<>();
        Map<Long, List<TaskTag>> pendingByItem = new LinkedHashMap<>();
        Map<Long, Integer> doneByItem = new LinkedHashMap<>();
        Set<String> pendingCodes = new LinkedHashSet<>();
        for (TaskModels.OpenTaskItemRow row : rows) {
            long tid = row.item().taskId();
            List<TaskTag> tags = tagCache.computeIfAbsent(tid, taskRepository::listTaskTags);
            Map<Long, List<ItemValue>> taskValues = valueCache.computeIfAbsent(tid, taskRepository::valuesByTask);
            Map<String, ItemValue> byTag = taskValues.getOrDefault(row.item().id(), List.of()).stream()
                    .collect(Collectors.toMap(ItemValue::tagCode, v -> v, (a, b) -> b, LinkedHashMap::new));
            valueByTag.put(row.item().id(), byTag);
            int done = (int) tags.stream().filter(t -> isApproved(byTag.get(t.tagCode()))).count();
            List<TaskTag> pending = tags.stream()
                    .filter(t -> includeOptional || t.required())
                    .filter(t -> !isApproved(byTag.get(t.tagCode())))
                    .toList();
            pending.forEach(t -> pendingCodes.add(t.tagCode()));
            pendingByItem.put(row.item().id(), pending);
            doneByItem.put(row.item().id(), done);
        }

        Map<String, TagMeta> metas = pendingCodes.isEmpty() ? Map.of()
                : tagMetaRepository.findByCodes(pendingCodes).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, m -> m, (a, b) -> a));

        List<TaskModels.OpenTaskTodoView> records = new ArrayList<>(rows.size());
        for (TaskModels.OpenTaskItemRow row : rows) {
            List<TaskModels.OpenTodoTag> pendingTags = pendingByItem
                    .getOrDefault(row.item().id(), List.of()).stream()
                    .map(tag -> toTodoTag(tag, metas.get(tag.tagCode()),
                            valueByTag.get(row.item().id()).get(tag.tagCode())))
                    .toList();
            int totalTags = tagCache.getOrDefault(row.item().taskId(), List.of()).size();
            records.add(new TaskModels.OpenTaskTodoView(row.item().taskId(), row.taskCode(), row.taskTitle(),
                    row.item().objectType(), row.item().objectId(), row.item().status(),
                    totalTags, doneByItem.getOrDefault(row.item().id(), 0), pendingTags));
        }
        return new TaskModels.OpenTaskTodoPage(pageNorm, sizeNorm, total, records);
    }

    /**
     * 「全部对象」任务的虚拟任务项。
     *
     * <p>语义：选了全部对象就不再按对象清单校验，只要类型匹配即属于该任务。因此对象即使
     * 从未写入、没有真实任务项，也应出现在待打标清单里，且全部必填标签都算「待打标」。
     * 虚拟项 id 用负数，避免与真实任务项 id 冲突，也不落库（下次查询重新生成）。</p>
     */
    private List<TaskModels.OpenTaskItemRow> allObjectsTodoRows(String tenantId, Long taskId,
                                                                List<String[]> pairs) {
        List<TaskModels.OpenTaskItemRow> out = new ArrayList<>();
        long virtualId = -1L;
        for (String[] pair : pairs) {
            String type = pair[0];
            String objectId = pair[1];
            if (type == null || objectId == null) {
                continue;
            }
            for (TaskRow task : taskRepository.listAllObjectsTasksWithoutItem(tenantId, type, objectId, taskId)) {
                // 没有目标标签的任务没有可打标项
                if (taskRepository.listTaskTags(task.id()).isEmpty()) {
                    continue;
                }
                out.add(new TaskModels.OpenTaskItemRow(
                        new TaskModels.ItemRow(virtualId--, task.id(), type, objectId, "pending",
                                null, null, null, null, null, null, null, null,
                                null, null, null, null, null),
                        task.taskCode(), task.title()));
            }
        }
        return out;
    }

    private static boolean isApproved(ItemValue value) {
        return value != null && "approved".equals(value.status());
    }

    private TaskModels.OpenTodoTag toTodoTag(TaskTag tag, TagMeta meta, ItemValue value) {
        return new TaskModels.OpenTodoTag(tag.tagCode(),
                meta == null ? tag.tagCode() : meta.tagName(),
                tag.required(),
                meta == null ? null : meta.valueType(),
                meta == null ? null : meta.unit(),
                TagOptions.of(meta, objectMapper),
                meta != null && meta.valueMultiple(),
                value == null ? null : value.tagValue(),
                value == null ? null : value.status());
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
        List<ItemValue> allValues = values.values().stream().flatMap(List::stream).toList();
        return new TaskModels.OpenItemPage(pageNorm, sizeNorm, taskRepository.countItems(taskId, status),
                taskRepository.stats(taskId), views, unitMapOf(allValues));
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
        if (Set.of("cancelled", "completed", "verified").contains(task.status())) {
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
            List<ItemValue> toWrite = values.stream()
                    .filter(value -> !"approved".equals(value.status()))
                    .toList();
            List<WriteRequest.WriteItem> writeItems = toWrite.stream().map(value -> new WriteRequest.WriteItem(
                    item.objectType(), item.objectId(), null, value.tagCode(), value.tagValue(),
                    null, null,
                    new OperatorDto(
                            item.operatorId() == null ? task.createdBy() : item.operatorId(),
                            item.operatorName() == null ? task.createdBy() : item.operatorName(),
                            "human",
                            item.operatorOrg()),
                    "adhoc".equals(task.taskType()) ? "workbench" : "workbench_task",
                    value.confidence(), value.remark(), value.evidenceUrl(),
                    "{\"task_id\":" + task.id() + ",\"item_id\":" + item.id() + "}")).toList();
            if (!writeItems.isEmpty()) {
                writeService.writeReviewed(new WriteRequest(bizRequestId, writeItems), tenantId,
                        RequestContext.getRequestId(), WorkbenchAccess.displayName(), request.remark());
            }
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
        if (!type.idPresent(objectId)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 不能为空（" + objectType + "）");
        }
        List<String> tagCodes = values.stream().map(ItemValue::tagCode).toList();
        requireTaskTags(tagCodes, tenantId, objectType);
        String code = nextTaskCode();
        String title = "临时打标审核 · " + objectId;
        long taskId = taskRepository.insertTask(code, tenantId, title, "工作台临时打标的敏感标签审核",
                objectType, "adhoc", "reviewing", "normal", "pool", null, null,
                null, true, false, 1, submitter.username(),
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

    // ------------------------------------------------------------------ open write link

    /** 写入前置校验：task_id 必须属于当前租户、处于可执行状态，对象与标签必须在任务内 */
    public void validateExternalWrite(String tenantId, long taskId, List<TaskWriteLinkHook.Item> items) {
        TaskRow task = requireTask(taskId, tenantId);
        if (!Set.of("published", "in_progress", "reviewing").contains(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "任务当前状态不接受写入: " + task.status());
        }
        Set<String> tags = taskRepository.listTaskTags(taskId).stream()
                .map(TaskModels.TaskTag::tagCode)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        for (TaskWriteLinkHook.Item item : items) {
            if (!task.objectType().equals(item.objectType())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "任务对象类型为 " + task.objectType() + "，与写入的 " + item.objectType() + " 不一致");
            }
            if (!tags.contains(item.tagCode())) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "标签 " + item.tagCode() + " 不属于任务 " + task.taskCode());
            }
            // 全部对象模式：不校验对象是否已在任务快照中，写入后由 afterWrite 自动纳入
            if (!task.allObjects() && taskRepository.findItemByObject(taskId, item.objectId()).isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "对象 " + item.objectId() + " 不在任务 " + task.taskCode() + " 中");
            }
        }
    }

    /**
     * 写入后联动：记录任务项值，任务要求的标签写齐后自动推进。
     * 任务 review_required 时进入 submitted 等审核（值已生效，审核只确认任务完成），否则直接 approved。
     */
    @Transactional
    public void applyExternalWrite(String tenantId, long taskId, List<TaskWriteLinkHook.Item> items) {
        TaskRow task = taskRepository.findTask(taskId).orElse(null);
        if (task == null || !Set.of("published", "in_progress", "reviewing").contains(task.status())) {
            return;
        }
        String operator = RequestContext.getAppKey();
        if (operator == null || operator.isBlank()) {
            operator = "open-api";
        }
        List<TaskModels.TaskTag> taskTags = taskRepository.listTaskTags(taskId);
        Set<String> required = taskTags.stream().filter(TaskModels.TaskTag::required)
                .map(TaskModels.TaskTag::tagCode).collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<TaskWriteLinkHook.Item>> byObject = items.stream()
                .collect(Collectors.groupingBy(TaskWriteLinkHook.Item::objectId, LinkedHashMap::new, Collectors.toList()));
        boolean changed = false;
        for (Map.Entry<String, List<TaskWriteLinkHook.Item>> entry : byObject.entrySet()) {
            Optional<TaskModels.ItemRow> found = taskRepository.findItemByObject(taskId, entry.getKey());
            TaskModels.ItemRow item;
            if (found.isPresent()) {
                item = found.get();
            } else if (task.allObjects() && entry.getValue().stream()
                    .allMatch(write -> task.objectType().equals(write.objectType()))) {
                // 全部对象模式：不校验对象是否已存在，写入即自动纳入任务
                item = taskRepository.createItem(taskId, task.objectType(), entry.getKey());
                objectIndexRepository.touch(tenantId, task.objectType(), entry.getKey());
                taskRepository.insertEvent(taskId, item.id(), "auto_item", null, item.status(), operator,
                        "全部对象模式：写入自动纳入对象 " + entry.getKey());
            } else {
                continue;
            }
            if (Set.of("submitted", "approved", "skipped").contains(item.status())) {
                continue;
            }
            for (TaskWriteLinkHook.Item write : entry.getValue()) {
                taskRepository.upsertExternalItemValue(item.id(), write.tagCode(), write.tagValue(),
                        write.remark(), write.evidenceUrl(), write.confidence());
            }
            taskRepository.insertEvent(taskId, item.id(), "external_write", item.status(), item.status(),
                    operator, "开放 API 写入 " + entry.getValue().size() + " 个标签值");
            List<ItemValue> values = taskRepository.listItemValues(item.id());
            Set<String> approved = values.stream()
                    .filter(v -> "approved".equals(v.status()))
                    .map(ItemValue::tagCode)
                    .collect(Collectors.toSet());
            boolean allWritten = required.stream().allMatch(approved::contains);
            if (allWritten) {
                taskRepository.markItemSubmitted(item.id(), operator, operator, null);
                if (task.reviewRequired()) {
                    taskRepository.insertEvent(taskId, item.id(), "submit", item.status(), "submitted",
                            operator, "外部写入标签已写齐，等待审核");
                } else {
                    taskRepository.markItemAutoApproved(item.id());
                    taskRepository.insertEvent(taskId, item.id(), "approve", "submitted", "approved",
                            "system", "外部写入标签已写齐，自动通过");
                }
                changed = true;
            } else {
                taskRepository.markItemInProgressExternal(item.id(), operator, operator);
                changed = true;
            }
        }
        if (changed) {
            refreshTaskStatus(taskId);
            auditLogService.record("TASK_EXTERNAL_WRITE", tenantId, task.objectType(), null, null,
                    "开放 API 写入推进任务 " + task.taskCode(), "success");
        }
    }

    /** 验收：仅已完成任务可被 ADMIN / TENANT_ADMIN 验收，验收后不可逆 */
    @Transactional
    public TaskView accept(long taskId, AcceptRequest request) {
        WorkbenchAccess.requireRole("ADMIN", "TENANT_ADMIN");
        String tenantId = WorkbenchAccess.requireTenant();
        TaskRow task = requireTask(taskId, tenantId);
        if (!"completed".equals(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "仅已完成任务可验收（当前 " + task.status() + "）");
        }
        String note = request == null ? null : trimToNull(request.note());
        taskRepository.markVerified(taskId);
        taskRepository.insertEvent(taskId, null, "accept", "completed", "verified",
                WorkbenchAccess.displayName(), note != null ? note : "验收通过");
        auditLogService.record("TASK_ACCEPT", tenantId, task.objectType(), null, null,
                "验收打标任务 " + task.taskCode(), "success");
        return view(taskId);
    }

    @Transactional
    public void refreshTaskStatus(long taskId) {
        TaskRow task = taskRepository.findTask(taskId).orElse(null);
        if (task == null) {
            return;
        }
        TaskStats stats = taskRepository.stats(taskId);
        taskRepository.updateStats(taskId);
        if (Set.of("draft", "cancelled", "completed", "verified").contains(task.status())) {
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
            if (!type.idPresent(id)) {
                if (invalid.size() < 5) {
                    invalid.add(id);
                }
            } else {
                ids.add(id);
            }
        }
        if (!invalid.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    field + "包含空的 object_id: " + String.join(", ", invalid));
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
