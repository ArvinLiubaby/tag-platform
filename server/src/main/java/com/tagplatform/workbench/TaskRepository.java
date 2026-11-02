package com.tagplatform.workbench;

import com.tagplatform.workbench.TaskModels.ItemRow;
import com.tagplatform.workbench.TaskModels.ItemValue;
import com.tagplatform.workbench.TaskModels.PendingValue;
import com.tagplatform.workbench.TaskModels.TaskEvent;
import com.tagplatform.workbench.TaskModels.TaskRow;
import com.tagplatform.workbench.TaskModels.TaskStats;
import com.tagplatform.workbench.TaskModels.TaskTag;
import com.tagplatform.workbench.TaskModels.UserWorkload;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * 打标任务仓储（tag_task / tag_task_tag / tag_task_item / tag_task_item_value / tag_task_event）。
 */
@Repository
public class TaskRepository {

    private static final RowMapper<TaskRow> TASK_MAPPER = (rs, rowNum) -> new TaskRow(
            rs.getLong("id"),
            rs.getString("task_code"),
            rs.getString("tenant_id"),
            rs.getString("title"),
            rs.getString("description"),
            rs.getString("object_type"),
            rs.getString("task_type"),
            rs.getString("status"),
            rs.getString("priority"),
            rs.getString("assignment_mode"),
            nullableLong(rs.getObject("assignee_user_id")),
            rs.getString("assignee_name"),
            rs.getString("team"),
            nullableLong(rs.getObject("reviewer_user_id")),
            rs.getString("reviewer_name"),
            rs.getInt("review_required") == 1,
            rs.getInt("total_items"),
            rs.getInt("completed_items"),
            rs.getInt("rejected_items"),
            toLdt(rs.getTimestamp("due_at")),
            toLdt(rs.getTimestamp("published_at")),
            toLdt(rs.getTimestamp("completed_at")),
            rs.getString("created_by"),
            toLdt(rs.getTimestamp("created_at")),
            toLdt(rs.getTimestamp("updated_at")));

    private static final RowMapper<ItemRow> ITEM_MAPPER = (rs, rowNum) -> new ItemRow(
            rs.getLong("id"),
            rs.getLong("task_id"),
            rs.getString("object_type"),
            rs.getString("object_id"),
            rs.getString("status"),
            nullableLong(rs.getObject("assignee_user_id")),
            rs.getString("assignee_name"),
            rs.getString("operator_id"),
            rs.getString("operator_name"),
            rs.getString("operator_org"),
            toLdt(rs.getTimestamp("claimed_at")),
            toLdt(rs.getTimestamp("submitted_at")),
            toLdt(rs.getTimestamp("reviewed_at")),
            nullableLong(rs.getObject("reviewer_user_id")),
            rs.getString("reviewer_name"),
            rs.getString("review_remark"),
            toLdt(rs.getTimestamp("created_at")),
            toLdt(rs.getTimestamp("updated_at")));

    private final JdbcTemplate jdbcTemplate;

    public TaskRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // ------------------------------------------------------------------ task

    public long insertTask(String taskCode, String tenantId, String title, String description,
                           String objectType, String taskType, String status, String priority,
                           String assignmentMode, Long assigneeUserId, String team, Long reviewerUserId,
                           boolean reviewRequired, int totalItems, String createdBy,
                           LocalDateTime publishedAt) {
        LocalDateTime now = LocalDateTime.now();
        KeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement("""
                    INSERT INTO tag_task (task_code, tenant_id, title, description, object_type, task_type,
                                          status, priority, assignment_mode, assignee_user_id, team,
                                          reviewer_user_id, review_required, total_items, completed_items,
                                          rejected_items, published_at, created_by, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 0, 0, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, taskCode);
            ps.setString(2, tenantId);
            ps.setString(3, title);
            ps.setString(4, description);
            ps.setString(5, objectType);
            ps.setString(6, taskType);
            ps.setString(7, status);
            ps.setString(8, priority);
            ps.setString(9, assignmentMode);
            ps.setObject(10, assigneeUserId);
            ps.setString(11, team);
            ps.setObject(12, reviewerUserId);
            ps.setInt(13, reviewRequired ? 1 : 0);
            ps.setInt(14, totalItems);
            ps.setTimestamp(15, publishedAt == null ? null : Timestamp.valueOf(publishedAt));
            ps.setString(16, createdBy);
            ps.setTimestamp(17, Timestamp.valueOf(now));
            ps.setTimestamp(18, Timestamp.valueOf(now));
            return ps;
        }, keyHolder);
        Number key = keyHolder.getKey();
        if (key == null) {
            throw new IllegalStateException("任务创建失败：未返回主键");
        }
        return key.longValue();
    }

    public void insertTaskTags(long taskId, List<String> tagCodes) {
        List<Object[]> args = new ArrayList<>();
        for (int i = 0; i < tagCodes.size(); i++) {
            args.add(new Object[]{taskId, tagCodes.get(i), 1, i + 1});
        }
        jdbcTemplate.batchUpdate(
                "INSERT INTO tag_task_tag (task_id, tag_code, required, sort_no) VALUES (?, ?, ?, ?)", args);
    }

    public void insertItems(long taskId, String objectType, List<String> objectIds) {
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> args = new ArrayList<>();
        for (String objectId : objectIds) {
            args.add(new Object[]{taskId, objectType, objectId, "pending", now, now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO tag_task_item (task_id, object_type, object_id, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?)
                """, args);
    }

    public Optional<TaskRow> findTask(long taskId) {
        return jdbcTemplate.query(taskSelect() + " WHERE t.id = ?", TASK_MAPPER, taskId)
                .stream().findFirst();
    }

    public List<TaskRow> listTasks(String tenantId, String status, Long assigneeUserId,
                                   boolean poolOrAssignee, int limit, int offset) {
        StringBuilder sql = new StringBuilder(taskSelect()).append(" WHERE t.tenant_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND t.status = ?");
            args.add(status);
        }
        if (assigneeUserId != null) {
            sql.append(poolOrAssignee
                    ? " AND (t.assignment_mode = 'pool' OR t.assignee_user_id = ?)"
                    : " AND t.assignee_user_id = ?");
            args.add(assigneeUserId);
        }
        sql.append(" ORDER BY t.created_at DESC LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        args.add(Math.max(offset, 0));
        return jdbcTemplate.query(sql.toString(), TASK_MAPPER, args.toArray());
    }

    public void markPublished(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE tag_task SET status='published', published_at=?, updated_at=? WHERE id=?",
                now, now, taskId);
    }

    public void updateTaskStatus(long taskId, String status) {
        jdbcTemplate.update("UPDATE tag_task SET status=?, updated_at=? WHERE id=?", status,
                LocalDateTime.now(), taskId);
    }

    public void markCompleted(long taskId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("UPDATE tag_task SET status='completed', completed_at=?, updated_at=? WHERE id=?",
                now, now, taskId);
    }

    public void updateAssignee(long taskId, Long assigneeUserId, String team, String assignmentMode) {
        jdbcTemplate.update("""
                UPDATE tag_task SET assignee_user_id=?, team=?, assignment_mode=?, updated_at=? WHERE id=?
                """, assigneeUserId, team, assignmentMode, LocalDateTime.now(), taskId);
    }

    public void updateDraft(long taskId, String title, String description, String priority, LocalDateTime dueAt) {
        jdbcTemplate.update("""
                UPDATE tag_task SET title=?, description=?, priority=?, due_at=?, updated_at=? WHERE id=?
                """, title, description, priority, dueAt == null ? null : Timestamp.valueOf(dueAt),
                LocalDateTime.now(), taskId);
    }

    /** 指定人派发：把未领取项预分配给该用户（仍保持 pending，需本人领取/提交） */
    public int assignPendingItems(long taskId, long userId) {
        return jdbcTemplate.update("""
                UPDATE tag_task_item SET assignee_user_id=?, updated_at=?
                 WHERE task_id=? AND status='pending' AND assignee_user_id IS NULL
                """, userId, LocalDateTime.now(), taskId);
    }

    public int clearPendingAssignees(long taskId) {
        return jdbcTemplate.update("""
                UPDATE tag_task_item SET assignee_user_id=NULL, updated_at=?
                 WHERE task_id=? AND status='pending'
                """, LocalDateTime.now(), taskId);
    }

    public void updateStats(long taskId) {
        TaskStats stats = stats(taskId);
        jdbcTemplate.update("""
                UPDATE tag_task SET total_items=?, completed_items=?, rejected_items=?, updated_at=?
                 WHERE id=?
                """, stats.total(), stats.approved() + stats.skipped(), stats.rejected(),
                LocalDateTime.now(), taskId);
    }

    public List<TaskTag> listTaskTags(long taskId) {
        return jdbcTemplate.query(
                "SELECT tag_code, required, sort_no FROM tag_task_tag WHERE task_id=? ORDER BY sort_no",
                (rs, rowNum) -> new TaskTag(rs.getString("tag_code"), rs.getInt("required") == 1,
                        rs.getInt("sort_no")), taskId);
    }

    // ------------------------------------------------------------------ item

    public long countTasks(String tenantId, String status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM tag_task WHERE tenant_id=?");
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND status=?");
            args.add(status);
        }
        Long count = jdbcTemplate.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public Optional<ItemRow> findItemByObject(long taskId, String objectId) {
        return jdbcTemplate.query(itemSelect() + " WHERE i.task_id = ? AND i.object_id = ?",
                ITEM_MAPPER, taskId, objectId).stream().findFirst();
    }

    /** 外部系统回传进度：completed（需审核则 submitted，否则 approved）/ skipped */
    public void markItemExternal(long itemId, String status, String operatorId, String operatorName,
                                 String operatorOrg, String remark) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime reviewedAt = ("approved".equals(status) || "skipped".equals(status)) ? now : null;
        jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status=?, operator_id=?, operator_name=?, operator_org=?,
                       submitted_at=?, reviewed_at=?, review_remark=?, updated_at=?
                 WHERE id=?
                """, status, operatorId, operatorName, operatorOrg, now, reviewedAt, remark, now, itemId);
    }

    public Optional<ItemRow> findItem(long itemId) {
        return jdbcTemplate.query(itemSelect() + " WHERE i.id = ?", ITEM_MAPPER, itemId)
                .stream().findFirst();
    }

    public List<ItemRow> listItems(long taskId) {
        return jdbcTemplate.query(itemSelect() + " WHERE i.task_id = ? ORDER BY i.id", ITEM_MAPPER, taskId);
    }

    public List<ItemRow> listItems(long taskId, String status, int limit, int offset) {
        StringBuilder sql = new StringBuilder(itemSelect()).append(" WHERE i.task_id = ?");
        List<Object> args = new ArrayList<>();
        args.add(taskId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status = ?");
            args.add(status);
        }
        sql.append(" ORDER BY i.id LIMIT ? OFFSET ?");
        args.add(Math.max(1, Math.min(limit, 500)));
        args.add(Math.max(offset, 0));
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    public long countItems(long taskId, String status) {
        Long count = status == null || status.isBlank()
                ? jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tag_task_item WHERE task_id=?",
                Long.class, taskId)
                : jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM tag_task_item WHERE task_id=? AND status=?", Long.class, taskId, status);
        return count == null ? 0L : count;
    }

    public List<ItemRow> listItemsByStatus(long taskId, String status) {
        return jdbcTemplate.query(itemSelect() + " WHERE i.task_id = ? AND i.status = ? ORDER BY i.id",
                ITEM_MAPPER, taskId, status);
    }

    /** 我的待办：指派给我的 + 任务池中未分配的 */
    public List<ItemRow> listItemsForUser(String tenantId, long userId, String status, int limit) {
        StringBuilder sql = new StringBuilder(itemSelect()).append("""
                 JOIN tag_task t ON t.id = i.task_id
                 WHERE t.tenant_id = ? AND t.status IN ('published','in_progress','reviewing')
                   AND (i.assignee_user_id = ? OR (i.assignee_user_id IS NULL AND t.assignment_mode = 'pool'))
                """);
        List<Object> args = new ArrayList<>();
        args.add(tenantId);
        args.add(userId);
        if (status != null && !status.isBlank()) {
            sql.append(" AND i.status = ?");
            args.add(status);
        } else {
            sql.append(" AND i.status IN ('pending','claimed','in_progress','submitted','rejected')");
        }
        sql.append(" ORDER BY i.updated_at DESC, i.id LIMIT ?");
        args.add(Math.max(1, Math.min(limit, 200)));
        return jdbcTemplate.query(sql.toString(), ITEM_MAPPER, args.toArray());
    }

    public Set<String> existingObjectIds(long taskId) {
        return new LinkedHashSet<>(jdbcTemplate.queryForList(
                "SELECT object_id FROM tag_task_item WHERE task_id=?", String.class, taskId));
    }

    public Map<Long, List<ItemValue>> valuesByTask(long taskId) {
        Map<Long, List<ItemValue>> result = new LinkedHashMap<>();
        jdbcTemplate.query("""
                SELECT v.item_id, v.tag_code, v.tag_value, v.remark, v.evidence_url, v.confidence, v.status
                  FROM tag_task_item_value v JOIN tag_task_item i ON i.id = v.item_id
                 WHERE i.task_id = ? ORDER BY v.item_id, v.tag_code
                """, rs -> {
                    long itemId = rs.getLong("item_id");
                    result.computeIfAbsent(itemId, key -> new ArrayList<>()).add(new ItemValue(
                            rs.getString("tag_code"), rs.getString("tag_value"), rs.getString("remark"),
                            rs.getString("evidence_url"), rs.getString("confidence"), rs.getString("status")));
                }, taskId);
        return result;
    }

    public int claimItem(long itemId, long userId, String operatorId, String operatorName, String operatorOrg) {
        LocalDateTime now = LocalDateTime.now();
        return jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status='claimed', assignee_user_id=?, operator_id=?, operator_name=?, operator_org=?,
                       claimed_at=?, updated_at=?
                 WHERE id=? AND status='pending'
                """, userId, operatorId, operatorName, operatorOrg, now, now, itemId);
    }

    public void markItemSubmitted(long itemId, String operatorId, String operatorName, String operatorOrg) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status='submitted', operator_id=?, operator_name=?, operator_org=?,
                       submitted_at=?, updated_at=?
                 WHERE id=?
                """, operatorId, operatorName, operatorOrg, now, now, itemId);
    }

    public void markItemApproved(long itemId, long reviewerId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status='approved', reviewer_user_id=?, reviewed_at=?, updated_at=?
                 WHERE id=?
                """, reviewerId, now, now, itemId);
    }

    /** 无需审核的直写项：提交即通过 */
    public void markItemAutoApproved(long itemId) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE tag_task_item SET status='approved', reviewed_at=?, updated_at=? WHERE id=?
                """, now, now, itemId);
    }

    public void markItemRejected(long itemId, long reviewerId, String remark) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status='rejected', reviewer_user_id=?, review_remark=?, reviewed_at=?, updated_at=?
                 WHERE id=?
                """, reviewerId, remark, now, now, itemId);
    }

    public void markItemSkipped(long itemId, long reviewerId, String remark) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
                UPDATE tag_task_item
                   SET status='skipped', reviewer_user_id=?, review_remark=?, reviewed_at=?, updated_at=?
                 WHERE id=?
                """, reviewerId, remark, now, now, itemId);
    }

    public void saveItemValues(long itemId, List<ItemValue> values) {
        jdbcTemplate.update("DELETE FROM tag_task_item_value WHERE item_id=?", itemId);
        LocalDateTime now = LocalDateTime.now();
        List<Object[]> args = new ArrayList<>();
        for (ItemValue value : values) {
            args.add(new Object[]{itemId, value.tagCode(), value.tagValue(), value.remark(),
                    value.evidenceUrl(), value.confidence(), value.status(), now, now});
        }
        jdbcTemplate.batchUpdate("""
                INSERT INTO tag_task_item_value (item_id, tag_code, tag_value, remark, evidence_url,
                                                 confidence, status, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, args);
    }

    public List<ItemValue> listItemValues(long itemId) {
        return jdbcTemplate.query("""
                SELECT tag_code, tag_value, remark, evidence_url, confidence, status
                  FROM tag_task_item_value WHERE item_id=? ORDER BY tag_code
                """, (rs, rowNum) -> new ItemValue(rs.getString("tag_code"), rs.getString("tag_value"),
                rs.getString("remark"), rs.getString("evidence_url"), rs.getString("confidence"),
                rs.getString("status")), itemId);
    }

    public void updateValueStatus(long itemId, String status) {
        jdbcTemplate.update("UPDATE tag_task_item_value SET status=?, updated_at=? WHERE item_id=?",
                status, LocalDateTime.now(), itemId);
    }

    // ------------------------------------------------------------------ event/stats

    public void insertEvent(long taskId, Long itemId, String eventType, String fromStatus,
                            String toStatus, String operator, String remark) {
        jdbcTemplate.update("""
                INSERT INTO tag_task_event (task_id, item_id, event_type, from_status, to_status,
                                            operator, remark, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, taskId, itemId, eventType, fromStatus, toStatus, operator, remark, LocalDateTime.now());
    }

    public List<TaskEvent> listEvents(long taskId, int limit) {
        return jdbcTemplate.query("""
                SELECT id, task_id, item_id, event_type, from_status, to_status, operator, remark, created_at
                  FROM tag_task_event WHERE task_id=? ORDER BY id DESC LIMIT ?
                """, (rs, rowNum) -> new TaskEvent(rs.getLong("id"), rs.getLong("task_id"),
                nullableLong(rs.getObject("item_id")), rs.getString("event_type"),
                rs.getString("from_status"), rs.getString("to_status"), rs.getString("operator"),
                rs.getString("remark"), toLdt(rs.getTimestamp("created_at"))), taskId,
                Math.max(1, Math.min(limit, 200)));
    }

    public TaskStats stats(long taskId) {
        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*) total,
                       COALESCE(SUM(CASE WHEN status='pending' THEN 1 ELSE 0 END),0) pending,
                       COALESCE(SUM(CASE WHEN status='claimed' THEN 1 ELSE 0 END),0) claimed,
                       COALESCE(SUM(CASE WHEN status='in_progress' THEN 1 ELSE 0 END),0) in_progress,
                       COALESCE(SUM(CASE WHEN status='submitted' THEN 1 ELSE 0 END),0) submitted,
                       COALESCE(SUM(CASE WHEN status='approved' THEN 1 ELSE 0 END),0) approved,
                       COALESCE(SUM(CASE WHEN status='rejected' THEN 1 ELSE 0 END),0) rejected,
                       COALESCE(SUM(CASE WHEN status='skipped' THEN 1 ELSE 0 END),0) skipped
                  FROM tag_task_item WHERE task_id=?
                """, (rs, rowNum) -> new TaskStats(rs.getLong("total"), rs.getLong("pending"),
                rs.getLong("claimed"), rs.getLong("in_progress"), rs.getLong("submitted"),
                rs.getLong("approved"), rs.getLong("rejected"), rs.getLong("skipped")), taskId);
    }

    public List<PendingValue> pendingValuesForObject(String tenantId, String objectType, String objectId) {
        return jdbcTemplate.query("""
                SELECT v.tag_code, COALESCE(m.tag_name, v.tag_code) tag_name, v.tag_value, v.remark,
                       v.status, t.id task_id, t.task_code, t.title, i.id item_id, i.operator_name,
                       v.updated_at
                  FROM tag_task_item_value v
                  JOIN tag_task_item i ON i.id = v.item_id
                  JOIN tag_task t ON t.id = i.task_id
                  LEFT JOIN tag_meta m ON m.tag_code = v.tag_code
                 WHERE t.tenant_id = ? AND i.object_type = ? AND i.object_id = ?
                   AND i.status = 'submitted' AND v.status = 'proposed'
                 ORDER BY v.updated_at DESC
                """, (rs, rowNum) -> new PendingValue(rs.getString("tag_code"), rs.getString("tag_name"),
                rs.getString("tag_value"), rs.getString("status"), rs.getLong("task_id"),
                rs.getLong("item_id"), rs.getString("task_code"), rs.getString("title"),
                rs.getString("operator_name"), rs.getString("remark"),
                toLdt(rs.getTimestamp("updated_at"))), tenantId, objectType, objectId);
    }

    public long countOpenItemsForUser(String tenantId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_task_item i JOIN tag_task t ON t.id = i.task_id
                 WHERE t.tenant_id = ? AND t.status IN ('published','in_progress','reviewing')
                   AND (i.assignee_user_id = ?
                       OR (i.assignee_user_id IS NULL AND t.assignment_mode = 'pool'))
                   AND i.status IN ('pending','claimed','in_progress','submitted','rejected')
                """, Long.class, tenantId, userId);
        return count == null ? 0L : count;
    }

    public long countOpenTasksForUser(String tenantId, long userId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT t.id) FROM tag_task t
                 WHERE t.tenant_id = ? AND t.status IN ('published','in_progress','reviewing')
                   AND (t.assignee_user_id = ? OR t.assignment_mode = 'pool'
                        OR EXISTS (SELECT 1 FROM tag_task_item i
                                    WHERE i.task_id = t.id AND i.assignee_user_id = ?))
                """, Long.class, tenantId, userId, userId);
        return count == null ? 0L : count;
    }

    public long countReviewItems(String tenantId) {
        Long count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_task_item i JOIN tag_task t ON t.id = i.task_id
                 WHERE t.tenant_id = ? AND i.status = 'submitted'
                """, Long.class, tenantId);
        return count == null ? 0L : count;
    }

    public TaskModels.TaskMonitor monitor(String tenantId) {
        Map<String, Long> statusCounts = new LinkedHashMap<>();
        jdbcTemplate.query("SELECT status, COUNT(*) cnt FROM tag_task WHERE tenant_id=? GROUP BY status",
                rs -> {
                    statusCounts.put(rs.getString("status"), rs.getLong("cnt"));
                }, tenantId);
        Long total = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM tag_task WHERE tenant_id=?",
                Long.class, tenantId);
        Long overdue = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_task
                 WHERE tenant_id=? AND due_at IS NOT NULL AND due_at < NOW()
                   AND status NOT IN ('completed','cancelled')
                """, Long.class, tenantId);
        Long pendingReview = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM tag_task_item i JOIN tag_task t ON t.id=i.task_id
                 WHERE t.tenant_id=? AND i.status='submitted'
                """, Long.class, tenantId);
        List<UserWorkload> workloads = jdbcTemplate.query("""
                SELECT u.id, u.display_name,
                       COUNT(i.id) assigned,
                       COALESCE(SUM(CASE WHEN i.status IN ('approved','skipped') THEN 1 ELSE 0 END),0) completed,
                       COALESCE(SUM(CASE WHEN i.status='rejected' THEN 1 ELSE 0 END),0) rejected
                  FROM tag_task_item i
                  JOIN tag_task t ON t.id = i.task_id
                  JOIN console_user u ON u.id = i.assignee_user_id
                 WHERE t.tenant_id = ?
                 GROUP BY u.id, u.display_name
                 ORDER BY assigned DESC
                """, (rs, rowNum) -> new UserWorkload(nullableLong(rs.getObject("id")),
                rs.getString("display_name"), rs.getLong("assigned"), rs.getLong("completed"),
                rs.getLong("rejected")), tenantId);
        List<TaskModels.TaskView> recent = listTasks(tenantId, null, null, false, 10, 0).stream()
                .map(row -> new TaskModels.TaskView(row, listTaskTags(row.id()), stats(row.id())))
                .toList();
        return new TaskModels.TaskMonitor(statusCounts, total == null ? 0 : total,
                overdue == null ? 0 : overdue, pendingReview == null ? 0 : pendingReview,
                workloads, recent);
    }

    // ------------------------------------------------------------------ sql helpers

    private String taskSelect() {
        return """
                SELECT t.*, au.display_name AS assignee_name, ru.display_name AS reviewer_name
                  FROM tag_task t
                  LEFT JOIN console_user au ON au.id = t.assignee_user_id
                  LEFT JOIN console_user ru ON ru.id = t.reviewer_user_id
                """;
    }

    private String itemSelect() {
        return """
                SELECT i.*, au.display_name AS assignee_name, ru.display_name AS reviewer_name
                  FROM tag_task_item i
                  LEFT JOIN console_user au ON au.id = i.assignee_user_id
                  LEFT JOIN console_user ru ON ru.id = i.reviewer_user_id
                """;
    }

    private static Long nullableLong(Object value) {
        return value == null ? null : ((Number) value).longValue();
    }

    private static LocalDateTime toLdt(Timestamp ts) {
        return ts == null ? null : ts.toLocalDateTime();
    }
}
