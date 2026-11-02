package com.tagplatform.workbench;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.model.ConsoleUser;
import com.tagplatform.console.repo.ConsoleUserRepository;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.query.service.ProfileQueryService;
import com.tagplatform.query.web.dto.ProfileView;
import com.tagplatform.workbench.TaskModels.ItemRow;
import com.tagplatform.workbench.TaskModels.ItemValue;
import com.tagplatform.workbench.TaskModels.PendingValue;
import com.tagplatform.workbench.TaskModels.SubmitRequest;
import com.tagplatform.workbench.TaskModels.SubmitResponse;
import com.tagplatform.workbench.TaskModels.SubmitResult;
import com.tagplatform.workbench.TaskModels.SubmitTag;
import com.tagplatform.workbench.TaskModels.SyncingValue;
import com.tagplatform.workbench.TaskModels.TaskItemContext;
import com.tagplatform.workbench.TaskModels.TaskItemView;
import com.tagplatform.workbench.TaskModels.TaskRow;
import com.tagplatform.workbench.TaskModels.TaskTag;
import com.tagplatform.workbench.TaskModels.TaskView;
import com.tagplatform.workbench.TaskModels.WorkbenchContext;
import com.tagplatform.workbench.TaskModels.WorkbenchItem;
import com.tagplatform.workbench.TaskModels.WorkbenchProfile;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.service.TagValueWriteService;
import com.tagplatform.write.service.ValueValidator;
import com.tagplatform.write.store.TagValueStore;
import com.tagplatform.write.web.dto.OperatorDto;
import com.tagplatform.write.web.dto.WriteRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 打标工作台：对象搜索、画像 overlay、动态标签、任务项领取与提交。
 * 提交复用统一写入链路；敏感标签在任务内先审后写，临时打标生成审核任务。
 */
@Service
public class WorkbenchService {

    private final TaskRepository taskRepository;
    private final TaskService taskService;
    private final TagMetaRepository tagMetaRepository;
    private final ObjectIndexRepository objectIndexRepository;
    private final ConsoleUserRepository userRepository;
    private final ProfileQueryService profileQueryService;
    private final TagValueStore tagValueStore;
    private final TagValueWriteService writeService;
    private final ValueValidator valueValidator;

    public WorkbenchService(TaskRepository taskRepository, TaskService taskService,
                            TagMetaRepository tagMetaRepository, ObjectIndexRepository objectIndexRepository,
                            ConsoleUserRepository userRepository, ProfileQueryService profileQueryService,
                            TagValueStore tagValueStore, TagValueWriteService writeService,
                            ValueValidator valueValidator) {
        this.taskRepository = taskRepository;
        this.taskService = taskService;
        this.tagMetaRepository = tagMetaRepository;
        this.objectIndexRepository = objectIndexRepository;
        this.userRepository = userRepository;
        this.profileQueryService = profileQueryService;
        this.tagValueStore = tagValueStore;
        this.writeService = writeService;
        this.valueValidator = valueValidator;
    }

    // ------------------------------------------------------------------ context

    public WorkbenchContext context(String requestedTenantId) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.resolveTenant(requestedTenantId);
        ConsoleUser user = taskService.requireCurrentUser();
        long pendingItems = taskRepository.countOpenItemsForUser(tenantId, user.id());
        String role = RequestContext.getConsoleRole();
        boolean reviewer = "ADMIN".equals(role) || "TENANT_ADMIN".equals(role)
                || "AUDITOR".equals(role) || "TASK_MANAGER".equals(role);
        return new WorkbenchContext(user.username(), user.displayName(), role, tenantId,
                user.operatorId(), user.operatorName(), user.operatorOrg(), user.team(),
                taskRepository.countOpenTasksForUser(tenantId, user.id()), pendingItems,
                reviewer ? taskRepository.countReviewItems(tenantId) : 0L);
    }

    public List<TaskModels.ObjectRow> objects(String objectType, String keyword, int limit) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.requireTenant();
        return objectIndexRepository.search(tenantId, trimToNull(objectType), keyword, limit);
    }

    public List<TagMeta> tags(String objectType) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.requireTenant();
        String type = trimToNull(objectType);
        return tagMetaRepository.listVisibleOnline(tenantId).stream()
                .filter(meta -> type == null || meta.supportsObjectType(type))
                .filter(meta -> !"derived".equalsIgnoreCase(meta.tagType()))
                .toList();
    }

    // ------------------------------------------------------------------ profile

    public WorkbenchProfile profile(String objectType, String objectId) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.requireTenant();
        return profileInternal(tenantId, objectType, objectId);
    }

    private WorkbenchProfile profileInternal(String tenantId, String objectType, String objectId) {
        ProfileView latest = profileQueryService.profile(tenantId, objectType, objectId, true);
        List<PendingValue> pending = taskRepository.pendingValuesForObject(tenantId, objectType, objectId);
        Set<String> pendingCodes = pending.stream().map(PendingValue::tagCode).collect(Collectors.toSet());
        Map<String, ProfileView.TagItem> latestByCode = latest.tags().stream()
                .collect(Collectors.toMap(ProfileView.TagItem::tagCode, item -> item, (a, b) -> a,
                        LinkedHashMap::new));

        List<TagValue> history = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                tenantId, null, objectType, objectId, null, null, null, 0, 200));
        LocalDateTime now = LocalDateTime.now();
        Map<String, TagValue> latestEffective = new LinkedHashMap<>();
        for (TagValue value : history) {
            if (!TagValue.STATUS_EFFECTIVE.equals(value.status())) {
                continue;
            }
            if (value.expireTime() != null && !value.expireTime().isAfter(now)) {
                continue;
            }
            latestEffective.putIfAbsent(value.tagCode(), value);
        }

        Map<String, TagMeta> metaMap = tagMetaRepository.findByCodes(latestEffective.keySet()).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, meta -> meta));
        List<SyncingValue> syncing = new ArrayList<>();
        for (TagValue value : latestEffective.values()) {
            if (pendingCodes.contains(value.tagCode())) {
                continue;
            }
            ProfileView.TagItem current = latestByCode.get(value.tagCode());
            boolean lagging = current == null
                    || current.operateTime() == null
                    || (value.opTime() != null && value.opTime().isAfter(current.operateTime()));
            if (!lagging) {
                continue;
            }
            TagMeta meta = metaMap.get(value.tagCode());
            syncing.add(new SyncingValue(value.tagCode(), meta == null ? value.tagCode() : meta.tagName(),
                    value.tagValue(), value.status(), value.opTime()));
        }
        return new WorkbenchProfile(latest, pending, syncing);
    }

    // ------------------------------------------------------------------ my tasks/items

    public List<TaskView> myTasks(String status, int limit, int offset) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.requireTenant();
        String role = RequestContext.getConsoleRole();
        boolean mine = "TAGGER".equals(role);
        return taskService.list(tenantId, status, mine, limit, offset);
    }

    public List<WorkbenchItem> myItems(String status, int limit) {
        WorkbenchAccess.requireWorkbench();
        String tenantId = WorkbenchAccess.requireTenant();
        ConsoleUser user = taskService.requireCurrentUser();
        List<ItemRow> items = taskRepository.listItemsForUser(tenantId, user.id(), status, limit);
        Map<Long, TaskRow> tasks = new LinkedHashMap<>();
        Map<Long, List<TaskTag>> tags = new LinkedHashMap<>();
        List<WorkbenchItem> result = new ArrayList<>();
        for (ItemRow item : items) {
            TaskRow task = tasks.computeIfAbsent(item.taskId(), id -> taskRepository.findTask(id).orElse(null));
            if (task == null) {
                continue;
            }
            List<TaskTag> taskTags = tags.computeIfAbsent(item.taskId(), taskRepository::listTaskTags);
            result.add(new WorkbenchItem(item, task, taskTags, taskRepository.listItemValues(item.id())));
        }
        return result;
    }

    public TaskItemContext itemContext(long itemId) {
        WorkbenchAccess.requireWorkbench();
        return taskService.itemContext(itemId);
    }

    // ------------------------------------------------------------------ claim/skip

    @Transactional
    public TaskItemView claim(long itemId) {
        WorkbenchAccess.requireTagging();
        String tenantId = WorkbenchAccess.requireTenant();
        ConsoleUser user = taskService.requireCurrentUser();
        ItemRow item = taskRepository.findItem(itemId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务项不存在: " + itemId));
        TaskRow task = taskService.requireTask(item.taskId(), tenantId);
        ensureTaskExecutable(task);
        ensureCanOperate(item, task, user);
        if ("claimed".equals(item.status()) && Objects.equals(item.assigneeUserId(), user.id())) {
            return new TaskItemView(item, taskRepository.listItemValues(itemId));
        }
        if (!"pending".equals(item.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务项不可领取（当前 " + item.status() + "）");
        }
        int updated = taskRepository.claimItem(itemId, user.id(), user.operatorId(), user.operatorName(),
                user.operatorOrg());
        if (updated == 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务项已被他人领取，请刷新列表");
        }
        taskRepository.insertEvent(task.id(), itemId, "claim", "pending", "claimed",
                user.displayName(), null);
        taskService.refreshTaskStatus(task.id());
        return new TaskItemView(taskRepository.findItem(itemId).orElse(item),
                taskRepository.listItemValues(itemId));
    }

    @Transactional
    public TaskItemView skip(long itemId, String remark) {
        WorkbenchAccess.requireTagging();
        String tenantId = WorkbenchAccess.requireTenant();
        ConsoleUser user = taskService.requireCurrentUser();
        ItemRow item = taskRepository.findItem(itemId)
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID, "任务项不存在: " + itemId));
        TaskRow task = taskService.requireTask(item.taskId(), tenantId);
        ensureTaskExecutable(task);
        ensureCanOperate(item, task, user);
        if ("submitted".equals(item.status()) || "approved".equals(item.status())
                || "skipped".equals(item.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "当前状态不可跳过: " + item.status());
        }
        String reason = trimToNull(remark);
        if (reason == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "跳过必须填写原因");
        }
        taskRepository.markItemSkipped(itemId, user.id(), reason);
        taskRepository.insertEvent(task.id(), itemId, "skip", item.status(), "skipped",
                user.displayName(), reason);
        taskService.refreshTaskStatus(task.id());
        return new TaskItemView(taskRepository.findItem(itemId).orElse(item),
                taskRepository.listItemValues(itemId));
    }

    // ------------------------------------------------------------------ submit

    @Transactional
    public SubmitResponse submit(SubmitRequest request) {
        WorkbenchAccess.requireTagging();
        String tenantId = WorkbenchAccess.requireTenant();
        ConsoleUser user = taskService.requireCurrentUser();
        if (request.tags() == null || request.tags().isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "至少提交一个标签值");
        }
        String bizRequestId = normalizeBizId(request.bizRequestId());
        if (request.taskItemId() != null) {
            return submitTask(tenantId, user, request, bizRequestId);
        }
        return submitAdhoc(tenantId, user, request, bizRequestId);
    }

    private SubmitResponse submitTask(String tenantId, ConsoleUser user, SubmitRequest request,
                                      String bizRequestId) {
        ItemRow item = taskRepository.findItem(request.taskItemId())
                .orElseThrow(() -> new BizException(ErrorCode.PARAM_INVALID,
                        "任务项不存在: " + request.taskItemId()));
        TaskRow task = taskService.requireTask(item.taskId(), tenantId);
        ensureTaskExecutable(task);
        ensureCanOperate(item, task, user);
        if ("submitted".equals(item.status()) || "approved".equals(item.status())
                || "skipped".equals(item.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务项当前状态不可提交: " + item.status());
        }
        if (request.objectType() != null && !request.objectType().isBlank()
                && !request.objectType().equals(item.objectType())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_type 与任务项不一致");
        }
        if (request.objectId() != null && !request.objectId().isBlank()
                && !request.objectId().equals(item.objectId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "object_id 与任务项不一致");
        }
        if ("pending".equals(item.status())) {
            int updated = taskRepository.claimItem(item.id(), user.id(), user.operatorId(),
                    user.operatorName(), user.operatorOrg());
            if (updated == 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "任务项已被他人领取，请刷新");
            }
            taskRepository.insertEvent(task.id(), item.id(), "claim", "pending", "claimed",
                    user.displayName(), "提交时自动领取");
        }

        List<TaskTag> taskTags = taskRepository.listTaskTags(task.id());
        Set<String> allowed = taskTags.stream().map(TaskTag::tagCode).collect(Collectors.toCollection(LinkedHashSet::new));
        List<PreparedTag> prepared = validateTags(request.tags(), tenantId, task.objectType(), allowed);
        for (TaskTag tag : taskTags) {
            if (tag.required() && prepared.stream().noneMatch(p -> p.meta().tagCode().equals(tag.tagCode()))) {
                throw tagError(tag.tagCode(), "任务必填标签未填写");
            }
        }
        boolean review = task.reviewRequired() || prepared.stream().anyMatch(p -> p.meta().needValueAudit());

        if (review) {
            taskRepository.saveItemValues(item.id(), prepared.stream()
                    .map(p -> p.value("proposed")).toList());
            taskRepository.markItemSubmitted(item.id(), user.operatorId(), user.operatorName(), user.operatorOrg());
            taskRepository.insertEvent(task.id(), item.id(), "submit", item.status(), "submitted",
                    user.displayName(), "提交 " + prepared.size() + " 个标签，等待审核");
            taskService.refreshTaskStatus(task.id());
            return new SubmitResponse("pending_review", RequestContext.getRequestId(), task.id(), item.id(),
                    prepared.stream().map(p -> new SubmitResult(p.meta().tagCode(), "pending_review",
                            "等待审核通过后写入")).toList());
        }

        writeDirect(prepared, tenantId, item.objectType(), item.objectId(), user,
                "workbench_task", "{\"task_id\":" + task.id() + ",\"item_id\":" + item.id() + "}", bizRequestId);
        taskRepository.saveItemValues(item.id(), prepared.stream().map(p -> p.value("approved")).toList());
        taskRepository.markItemSubmitted(item.id(), user.operatorId(), user.operatorName(), user.operatorOrg());
        taskRepository.markItemAutoApproved(item.id());
        taskRepository.insertEvent(task.id(), item.id(), "submit", item.status(), "submitted",
                user.displayName(), "提交 " + prepared.size() + " 个标签");
        taskRepository.insertEvent(task.id(), item.id(), "approve", "submitted", "approved",
                "system", "普通标签免审，提交即生效");
        taskService.refreshTaskStatus(task.id());
        return new SubmitResponse("effective", RequestContext.getRequestId(), task.id(), item.id(),
                prepared.stream().map(p -> new SubmitResult(p.meta().tagCode(), "effective", null)).toList());
    }

    private SubmitResponse submitAdhoc(String tenantId, ConsoleUser user, SubmitRequest request,
                                       String bizRequestId) {
        String objectType = trimToNull(request.objectType());
        String objectId = trimToNull(request.objectId());
        if (objectType == null || objectId == null) {
            throw new BizException(ErrorCode.PARAM_MISSING, "object_type 与 object_id 不能为空");
        }
        ObjectTypeMeta type = taskService.requireObjectType(objectType);
        if (!type.idMatches(objectId)) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "object_id 不符合 " + objectType + " 的格式要求: " + type.idPattern());
        }
        List<PreparedTag> prepared = validateTags(request.tags(), tenantId, objectType, null);
        List<PreparedTag> normal = prepared.stream().filter(p -> !p.meta().needValueAudit()).toList();
        List<PreparedTag> sensitive = prepared.stream().filter(p -> p.meta().needValueAudit()).toList();

        List<SubmitResult> results = new ArrayList<>();
        if (!normal.isEmpty()) {
            writeDirect(normal, tenantId, objectType, objectId, user, "workbench",
                    "{\"workbench\":true}", bizRequestId);
            normal.forEach(p -> results.add(new SubmitResult(p.meta().tagCode(), "effective", null)));
        }
        Long taskId = null;
        Long itemId = null;
        if (!sensitive.isEmpty()) {
            TaskItemContext context = taskService.createAdhocReview(tenantId, objectType, objectId,
                    sensitive.stream().map(p -> p.value("proposed")).toList(), user);
            taskId = context.task().id();
            itemId = context.item().id();
            sensitive.forEach(p -> results.add(new SubmitResult(p.meta().tagCode(), "pending_review",
                    "敏感标签，审核通过后写入")));
        }
        objectIndexRepository.touch(tenantId, objectType, objectId);
        return new SubmitResponse(sensitive.isEmpty() ? "effective" : "pending_review",
                RequestContext.getRequestId(), taskId, itemId, results);
    }

    private void writeDirect(List<PreparedTag> prepared, String tenantId, String objectType, String objectId,
                             ConsoleUser user, String source, String extJson, String bizRequestId) {
        String operatorId = firstNonBlank(user.operatorId(), user.username());
        String operatorName = firstNonBlank(user.operatorName(), user.displayName());
        List<WriteRequest.WriteItem> items = prepared.stream().map(p -> new WriteRequest.WriteItem(
                objectType, objectId, p.meta().tagCode(), p.value().tagValue(), null, null,
                new OperatorDto(operatorId, operatorName, "human", user.operatorOrg()),
                source, p.value().confidence(), p.value().remark(), p.value().evidenceUrl(),
                extJson)).toList();
        writeService.write(new WriteRequest(bizRequestId, items), tenantId, RequestContext.getRequestId());
    }

    // ------------------------------------------------------------------ validation

    private List<PreparedTag> validateTags(List<SubmitTag> tags, String tenantId, String objectType,
                                           Set<String> allowedCodes) {
        Map<String, TagMeta> metas = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        List<PreparedTag> result = new ArrayList<>();
        for (SubmitTag tag : tags) {
            if (tag == null) {
                throw new BizException(ErrorCode.PARAM_INVALID, "标签条目不能为空");
            }
            String code = trimToNull(tag.tagCode());
            if (code == null) {
                throw new BizException(ErrorCode.PARAM_MISSING, "tag_code 不能为空");
            }
            if (!seen.add(code)) {
                throw tagError(code, "同一标签不能重复提交");
            }
            if (allowedCodes != null && !allowedCodes.contains(code)) {
                throw tagError(code, "不在任务目标标签内");
            }
            TagMeta meta = metas.computeIfAbsent(code, c -> tagMetaRepository.findByCode(c).orElse(null));
            if (meta == null || !meta.visibleTo(tenantId)) {
                throw tagError(code, "标签不存在或当前租户不可用");
            }
            if (!meta.online()) {
                throw tagError(code, "标签未上线");
            }
            if (!meta.writable()) {
                throw tagError(code, "标签不允许写入（写入开关已关闭）");
            }
            if (!meta.supportsObjectType(objectType)) {
                throw tagError(code, "标签不适用于对象类型 " + objectType);
            }
            String valueError = valueValidator.validate(meta, tag.tagValue());
            if (valueError != null) {
                throw tagError(code, valueError);
            }
            String remark = trimToNull(tag.remark());
            if (meta.needValueAudit() && remark == null) {
                throw tagError(code, "敏感标签必须填写打标理由");
            }
            if (remark != null && remark.length() > 2048) {
                throw tagError(code, "remark 超长（上限 2048）");
            }
            String evidenceUrl = trimToNull(tag.evidenceUrl());
            if (evidenceUrl != null) {
                if (evidenceUrl.length() > 512) {
                    throw tagError(code, "evidence_url 超长（上限 512）");
                }
                String lower = evidenceUrl.toLowerCase(Locale.ROOT);
                if (!lower.startsWith("http://") && !lower.startsWith("https://")) {
                    throw tagError(code, "evidence_url 仅支持 http/https");
                }
            }
            String confidence = trimToNull(tag.confidence());
            if (confidence != null && !TagValue.CONFIDENCE_LEVELS.contains(confidence)) {
                throw tagError(code, "confidence 仅支持：高/中/低");
            }
            String normalized = valueValidator.normalize(meta, tag.tagValue());
            result.add(new PreparedTag(meta, new ItemValue(code, normalized, remark, evidenceUrl,
                    confidence, "proposed")));
        }
        if (result.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "至少提交一个标签值");
        }
        return result;
    }

    // ------------------------------------------------------------------ access helpers

    private void ensureTaskExecutable(TaskRow task) {
        if (!Set.of("published", "in_progress", "reviewing").contains(task.status())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "任务当前状态不可执行: " + task.status());
        }
    }

    private void ensureCanOperate(ItemRow item, TaskRow task, ConsoleUser user) {
        if ("ADMIN".equals(user.userRole())) {
            return;
        }
        if (!"TAGGER".equals(user.userRole()) && !"TASK_MANAGER".equals(user.userRole())
                && !"TENANT_ADMIN".equals(user.userRole())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "当前角色不能执行打标");
        }
        if (item.assigneeUserId() != null && !item.assigneeUserId().equals(user.id())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "任务项已分配给其他打标员");
        }
        if (task.assigneeUserId() != null && !task.assigneeUserId().equals(user.id())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "任务已指派给其他打标员");
        }
        if ("team".equals(task.assignmentMode()) && task.team() != null
                && !task.team().equals(user.team())) {
            throw new BizException(ErrorCode.CONSOLE_FORBIDDEN, "仅任务班组可执行该任务");
        }
    }

    private static BizException tagError(String tagCode, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("tag_code", tagCode);
        data.put("reason", message);
        return new BizException(ErrorCode.PARAM_INVALID, "标签 " + tagCode + ": " + message, data);
    }

    private String normalizeBizId(String value) {
        String biz = trimToNull(value);
        if (biz == null) {
            biz = "wb-" + UUID.randomUUID().toString().replace("-", "");
        }
        if (biz.length() > 96) {
            throw new BizException(ErrorCode.PARAM_INVALID, "biz_request_id 长度不能超过 96");
        }
        return biz;
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private record PreparedTag(TagMeta meta, ItemValue value) {
        ItemValue value(String status) {
            return new ItemValue(value.tagCode(), value.tagValue(), value.remark(), value.evidenceUrl(),
                    value.confidence(), status);
        }
    }
}
