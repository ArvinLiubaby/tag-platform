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

    public record TaskTag(String tagCode, boolean required, int sortNo) {
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

    /** 开放 API：任务分页 */
    public record OpenTaskPage(int page, int size, long total, List<TaskView> records) {
    }

    /** 开放 API：任务对象项分页（含已写入值） */
    public record OpenItemPage(int page, int size, long total, TaskStats stats, List<TaskItemView> records) {
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
