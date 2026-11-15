package com.tagplatform.write.store;

import com.tagplatform.write.model.TagValue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * 标签值存储抽象：生产 StarRocks（PK 模型），demo H2（MySQL 模式）。
 * 职责：流水追加、画像/审核/流水查询；最新值 upsert 仅供外部写入程序或测试调用。
 */
public interface TagValueStore {

    /** 最新值 upsert（last-write-wins）；生产由外部写入程序维护，应用侧仅测试使用 */
    void upsertLatest(TagValue value);

    /** 追加变更流水 */
    void insertHistory(TagValue value);

    Optional<TagValue> findLatest(String tenantId, String objectType, String objectId, String tagCode);

    List<TagValue> listLatestByObject(String tenantId, String objectType, String objectId);

    /** 某标签的当前值（tag_value_latest），按更新时间倒序 */
    List<TagValue> listLatestByTag(String tenantId, String tagCode, int limit);

    /** 某标签当前值条数 */
    long countLatestByTag(String tenantId, String tagCode);

    /**
     * 对象清单：从 latest 聚合出「该租户下有哪些对象」，作为对象下拉/工作台最近对象的<b>事实源</b>。
     *
     * <p>用 latest 而不是 object_index，是因为 object_index 只在平台写入链路里登记；
     * 外部程序直写 latest 的对象不会出现在索引里，但一定在 latest 里。</p>
     *
     * @param objectType 对象类型，为空表示不限
     * @param keyword    对象 ID 模糊匹配，可为空
     * @param limit      返回条数上限（按最近出现时间倒序）
     */
    List<ObjectRef> listObjects(String tenantId, String objectType, String keyword, int limit);

    /** latest 中的对象引用：类型、ID、名称（可能为空）、首末次出现时间 */
    record ObjectRef(String objectType, String objectId, String objectName,
                     LocalDateTime firstSeen, LocalDateTime lastSeen) {
    }

    /** 待审核队列（控制台审核员使用；tenantId 为空时返回全租户） */
    List<TagValue> listPendingAudit(int limit);

    List<TagValue> listPendingAudit(String tenantId, int limit);

    List<TagValue> queryHistory(HistoryQuery query);

    /** 满足条件的流水总数（分页用） */
    long countHistory(HistoryQuery query);

    /**
     * 手动同步：把打标流水按「同一对象 + 同一标签取最后一条（op_time, ver）」折叠后写入 latest。
     *
     * <p>正常链路里 latest 由外部同步程序维护，本方法是给控制台的**一次性手动触发**兜底，
     * 不做定时任务。objectType / objectId 为空表示同步该租户全量；返回写入行数。</p>
     */
    int syncLatestFromHistory(String tenantId, String objectType, String objectId);

    /** 流水查询条件（operatorId 等条件可选，op_time 倒序，offset/limit 分页） */
    record HistoryQuery(
            String tenantId,
            String operatorId,
            String objectType,
            String objectId,
            String tagCode,
            LocalDateTime from,
            LocalDateTime to,
            int offset,
            int limit) {
    }
}
