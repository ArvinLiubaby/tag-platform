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

    /** 待审核队列（控制台审核员使用；tenantId 为空时返回全租户） */
    List<TagValue> listPendingAudit(int limit);

    List<TagValue> listPendingAudit(String tenantId, int limit);

    List<TagValue> queryHistory(HistoryQuery query);

    /** 满足条件的流水总数（分页用） */
    long countHistory(HistoryQuery query);

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
