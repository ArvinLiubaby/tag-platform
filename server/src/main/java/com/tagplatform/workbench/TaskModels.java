package com.tagplatform.workbench;

import com.tagplatform.query.web.dto.ProfileView;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 打标工作台 / 任务管理的数据结构。
 */
public final class TaskModels {

    private TaskModels() {
    }

    public record TaskRow(
            long id,
            String taskCode,
            String tenantId,
            String title,
            String description,
            String objectType,
            String taskType,
            String status,
            String priority,
            String assignmentMode,
            Long assigneeUserId,
            String assigneeName,
            String team,
            Long reviewerUserId,
            String reviewerName,
            boolean reviewRequired,
            boolean allObjects,
            int totalItems,
            int completedItems,
            int rejectedItems,
            LocalDateTime dueAt,
            LocalDateTime publishedAt,
            LocalDateTime completedAt,
            String createdBy,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    /**
     * 任务标签（快照 + 字典回填）。tag_task_tag 快照只存 tag_code/required/sort_no；
     * tag_name、value_type、unit、value_range、value_multiple、options 来自 tag_meta 当前定义
     * （枚举值域 options 供外部直接渲染填写控件；标签已下线/删除时字典字段为 null，tag_code 仍返回）。
     */
    public record TaskTag(String tagCode, String tagName, boolean required, String valueType,
                          String unit, String valueRange, boolean valueMultiple,
                          List<String> options, int sortNo) {
    }

    public record ItemRow(
            long id,
            long taskId,
            String objectType,
            String objectId,
            String status,
            Long assigneeUserId,
            String assigneeName,
            String operatorId,
            String operatorName,
            String operatorOrg,
            LocalDateTime claimedAt,
            LocalDateTime submittedAt,
            LocalDateTime reviewedAt,
            Long reviewerUserId,
            String reviewerName,
            String reviewRemark,
            LocalDateTime createdAt,
            LocalDateTime updatedAt) {
    }

    public record ItemValue(
            String tagCode,
            String tagValue,
            String remark,
            String evidenceUrl,
            String confidence,
            String status) {
    }

    public record TaskEvent(
            long id,
            long taskId,
            Long itemId,
            String eventType,
            String fromStatus,
            String toStatus,
            String operator,
            String remark,
            LocalDateTime createdAt) {
    }

    public record TaskStats(
            long total,
            long pending,
            long claimed,
            long inProgress,
            long submitted,
            long approved,
            long rejected,
            long skipped) {
    }

    public record TaskDetail(TaskRow task, List<TaskTag> tags, List<TaskItemView> items,
                             TaskStats stats, List<TaskEvent> events) {
    }

    public record TaskItemView(ItemRow item, List<ItemValue> values) {
    }

    public record TaskView(TaskRow task, List<TaskTag> tags, TaskStats stats) {
    }

    /** 开放 API：任务分页（带客服个人进度时 myStats 非空） */
    public record OpenTaskPage(int page, int size, long total, List<OpenTaskView> records) {
    }

    /** 开放 API：任务视图 + 指定客服/对象维度的个人进度 */
    public record OpenTaskView(TaskRow task, List<TaskTag> tags, TaskStats stats, TaskStats myStats) {
    }

    /** 开放 API：跨任务的对象项视图 */
    public record OpenTaskItemView(long taskId, String taskCode, String taskTitle,
                                   ItemRow item, List<ItemValue> values) {
    }

    /** 开放 API：对象项分页；units 为「标签码 → 计量单位」映射（仅含配置了单位的标签） */
    public record OpenTaskItemPage(int page, int size, long total, List<OpenTaskItemView> records,
                                   Map<String, String> units) {
    }

    /** 开放 API：对象项原始行（含任务编码/标题） */
    public record OpenTaskItemRow(ItemRow item, String taskCode, String taskTitle) {
    }

    /** 开放 API：任务对象项分页（含已写入值）；units 为「标签码 → 计量单位」映射 */
    public record OpenItemPage(int page, int size, long total, TaskStats stats, List<TaskItemView> records,
                               Map<String, String> units) {
    }

    /**
     * 开放 API：待打标清单。一个对象一条，pendingTags 为「还缺的标签」（必填未写 + 被驳回）。
     * 按对象分页，total = 仍有待打标标签的对象数。
     */
    public record OpenTaskTodoPage(int page, int size, long total, List<OpenTaskTodoView> records) {
    }

    public record OpenTaskTodoView(long taskId, String taskCode, String taskTitle,
                                   String objectType, String objectId, String itemStatus,
                                   int totalTags, int doneTags, List<OpenTodoTag> pendingTags) {
    }

    /** 待打标标签：元数据只带渲染必需项，完整字典请用 GET /open/tags */
    public record OpenTodoTag(String tagCode, String tagName, boolean required, String valueType, String unit,
                              List<String> options, boolean valueMultiple,
                              String lastValue, String lastStatus) {
    }

    /** 开放 API：外部系统回传任务进度 */
    public record ExternalProgressRequest(List<ExternalProgressItem> items) {
    }

    public record ExternalProgressItem(String objectId, String status, String remark) {
    }

    public record ExternalProgressResult(long taskId, int updated, int unchanged, int notFound,
                                         TaskStats stats, List<String> notFoundObjects) {
    }

    public record CreateRequest(
            String tenantId,
            String title,
            String description,
            String objectType,
            String priority,
            String assignmentMode,
            Long assigneeUserId,
            String team,
            Long reviewerUserId,
            boolean reviewRequired,
            LocalDateTime dueAt,
            List<String> tagCodes,
            List<String> objectIds,
            boolean allObjects,
            boolean publish) {
    }

    public record AssignRequest(Long assigneeUserId, String team) {
    }

    public record ImportRequest(List<String> objectIds, String csv) {
    }

    public record TaskUpdateRequest(String title, String description, String priority, LocalDateTime dueAt) {
    }

    public record SubmitResult(String tagCode, String status, String message) {
    }

    public record SubmitResponse(String status, String requestId, Long taskId, Long taskItemId,
                                 List<SubmitResult> results) {
    }

    public record SubmitTag(String tagCode, String tagValue, String remark, String evidenceUrl, String confidence) {
    }

    public record SubmitRequest(String bizRequestId, Long taskItemId, String objectType, String objectId,
                                List<SubmitTag> tags) {
    }

    public record ReviewRequest(boolean approved, String remark) {
    }

    public record AcceptRequest(String note) {
    }

    public record PendingValue(
            String tagCode,
            String tagName,
            String tagValue,
            String status,
            long taskId,
            long taskItemId,
            String taskCode,
            String taskTitle,
            String operatorName,
            String remark,
            LocalDateTime submittedAt) {
    }

    public record SyncingValue(
            String tagCode,
            String tagName,
            String tagValue,
            String status,
            LocalDateTime opTime) {
    }

    public record WorkbenchProfile(ProfileView latest, List<PendingValue> pending, List<SyncingValue> syncing) {
    }

    public record WorkbenchContext(
            String username,
            String displayName,
            String role,
            String tenantId,
            String operatorId,
            String operatorName,
            String operatorOrg,
            String team,
            long myTaskCount,
            long myPendingItemCount,
            long reviewItemCount) {
    }

    public record ObjectRow(String tenantId, String objectType, String objectId,
                            LocalDateTime firstSeen, LocalDateTime lastSeen) {
    }

    public record ObjectIndexPage(long total, List<ObjectRow> items) {
    }

    public record TaskItemContext(ItemRow item, TaskRow task, List<TaskTag> tags, List<ItemValue> values) {
    }

    public record ItemPage(int page, int size, long total, List<TaskItemView> items) {
    }

    public record WorkbenchItem(ItemRow item, TaskRow task, List<TaskTag> tags, List<ItemValue> values) {
    }

    public record UserWorkload(Long userId, String displayName, long assigned, long completed, long rejected) {
    }

    public record TaskMonitor(
            Map<String, Long> statusCounts,
            long total,
            long overdue,
            long pendingReview,
            List<UserWorkload> workloads,
            List<TaskView> recentTasks) {
    }
}
