package com.tagplatform.write.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.access.RateLimiter;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.config.AppProperties;
import com.tagplatform.meta.model.ObjectTypeMeta;
import com.tagplatform.meta.model.TagMeta;
import com.tagplatform.meta.repo.ObjectTypeRepository;
import com.tagplatform.meta.repo.TagMetaRepository;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import com.tagplatform.write.web.dto.DeleteRequest;
import com.tagplatform.write.web.dto.OperatorDto;
import com.tagplatform.write.web.dto.WriteRequest;
import com.tagplatform.write.web.dto.WriteResult;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 标签值写入/作废核心服务：
 *   幂等（biz_request_id）→ 批量校验（全过才受理）→ 状态路由（敏感标签记 pending_audit）
 *   → 追加 history + Outbox 事件 → 响应快照落幂等表。
 *   tag_value_latest 由外部程序维护，本服务不再写入。
 */
@Service
public class TagValueWriteService {

    private static final Set<String> OPERATOR_TYPES = Set.of("human", "system", "algo");

    private final TagMetaRepository tagMetaRepository;
    private final ObjectTypeRepository objectTypeRepository;
    private final ValueValidator valueValidator;
    private final RateLimiter rateLimiter;
    private final TagValueStore tagValueStore;
    private final IdempotencyService idempotencyService;
    private final OutboxService outboxService;
    private final ObjectMapper objectMapper;
    private final AppProperties props;

    public TagValueWriteService(TagMetaRepository tagMetaRepository, ObjectTypeRepository objectTypeRepository,
                                ValueValidator valueValidator, RateLimiter rateLimiter, TagValueStore tagValueStore,
                                IdempotencyService idempotencyService, OutboxService outboxService,
                                ObjectMapper objectMapper, AppProperties props) {
        this.tagMetaRepository = tagMetaRepository;
        this.objectTypeRepository = objectTypeRepository;
        this.valueValidator = valueValidator;
        this.rateLimiter = rateLimiter;
        this.tagValueStore = tagValueStore;
        this.idempotencyService = idempotencyService;
        this.outboxService = outboxService;
        this.objectMapper = objectMapper;
        this.props = props;
    }

    // ------------------------------------------------------------------ write

    public WriteResult write(WriteRequest request, String tenantId, String requestId) {
        String bizRequestId = requireBizRequestId(request.bizRequestId());
        if (!idempotencyService.tryClaim(bizRequestId, tenantId)) {
            return replayOrThrow(bizRequestId);
        }
        try {
            WriteResult result = doWrite(request, tenantId, requestId, bizRequestId, null);
            idempotencyService.complete(bizRequestId, toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotencyService.release(bizRequestId);
            throw e;
        }
    }

    /**
     * 任务审核通过后的写入：忽略 need_value_audit，直接写 effective，
     * 并把审核人/时间/意见写入流水。用于“先审后写”的工作台任务审核。
     */
    public WriteResult writeReviewed(WriteRequest request, String tenantId, String requestId,
                                     String auditBy, String auditRemark) {
        String bizRequestId = requireBizRequestId(request.bizRequestId());
        if (!idempotencyService.tryClaim(bizRequestId, tenantId)) {
            return replayOrThrow(bizRequestId);
        }
        try {
            WriteResult result = doWrite(request, tenantId, requestId, bizRequestId,
                    new ReviewInfo(auditBy, auditRemark));
            idempotencyService.complete(bizRequestId, toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotencyService.release(bizRequestId);
            throw e;
        }
    }

    private WriteResult doWrite(WriteRequest request, String tenantId, String requestId, String bizRequestId,
                                ReviewInfo review) {
        List<WriteRequest.WriteItem> items = request.items();
        checkBatch(items);

        Map<String, TagMeta> tagMap = loadTags(items);
        Map<String, ObjectTypeMeta> objectTypeMap = loadObjectTypes(items);

        // 操作员频控（按去重后的操作员集合，控制批量场景误杀）
        Set<String> operatorIds = items.stream()
                .map(WriteRequest.WriteItem::operator)
                .filter(Objects::nonNull)
                .map(OperatorDto::operatorId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        for (String operatorId : operatorIds) {
            rateLimiter.checkOperator(tenantId, operatorId);
        }

        LocalDateTime now = LocalDateTime.now();
        List<PreparedItem> prepared = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            prepared.add(prepare(items.get(i), i, tenantId, requestId, now, tagMap, objectTypeMap, review));
        }

        // 只写变更流水；tag_value_latest 由外部程序消费后自行维护
        for (PreparedItem p : prepared) {
            tagValueStore.insertHistory(p.value());
            RequestContext.recordTagWrite(p.value().tagCode());
            RequestContext.setOperatorId(p.value().operatorId());
            RequestContext.setObject(p.value().objectType(), p.value().objectId());
        }
        for (PreparedItem p : prepared) {
            outboxService.publish(p.pendingAudit() ? "TAG_VALUE_PENDING_AUDIT" : "TAG_VALUE_WRITTEN",
                    tenantId, eventOf(p.value()));
        }

        List<WriteResult.ItemResult> results = new ArrayList<>(prepared.size());
        int pendingCount = 0;
        for (int i = 0; i < prepared.size(); i++) {
            PreparedItem p = prepared.get(i);
            if (p.pendingAudit()) {
                pendingCount++;
            }
            results.add(new WriteResult.ItemResult(i, p.value().objectType(), p.value().objectId(), p.value().tagCode(),
                    p.pendingAudit() ? "pending_audit" : "accepted",
                    p.pendingAudit() ? "待人工审核后生效" : null));
        }
        return new WriteResult(bizRequestId, prepared.size(), prepared.size() - pendingCount, pendingCount, results);
    }

    private PreparedItem prepare(WriteRequest.WriteItem item, int index, String tenantId, String requestId,
                                 LocalDateTime now, Map<String, TagMeta> tagMap,
                                 Map<String, ObjectTypeMeta> objectTypeMap, ReviewInfo review) {
        if (item == null) {
            throw itemError(index, null, ErrorCode.PARAM_INVALID, "条目不能为 null");
        }
        if (isBlank(item.objectType())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "object_type 不能为空");
        }
        if (isBlank(item.objectId())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "object_id 不能为空");
        }
        if (isBlank(item.tagCode())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "tag_code 不能为空");
        }
        ObjectTypeMeta objectType = objectTypeMap.get(item.objectType());
        if (objectType == null) {
            throw itemError(index, item, ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                    "对象类型未注册: " + item.objectType());
        }
        if (!objectType.enabled()) {
            throw itemError(index, item, ErrorCode.OBJECT_TYPE_NOT_REGISTERED,
                    "对象类型已停用: " + item.objectType());
        }
        if (!objectType.idMatches(item.objectId())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID,
                    "object_id 不符合 " + item.objectType() + " 的格式要求: " + objectType.idPattern());
        }
        TagMeta tag = tagMap.get(item.tagCode());
        if (tag == null || !tag.visibleTo(tenantId)) {
            throw itemError(index, item, ErrorCode.TAG_NOT_FOUND, "标签不存在或不可用: " + item.tagCode());
        }
        if (!tag.online()) {
            throw itemError(index, item, ErrorCode.TAG_NOT_ONLINE, "标签未上线: " + item.tagCode());
        }
        if (!tag.writable()) {
            throw itemError(index, item, ErrorCode.TAG_WRITE_DISABLED,
                    "标签 " + item.tagCode() + " 不允许写入（写入开关已关闭）");
        }
        if (!tag.supportsObjectType(item.objectType())) {
            throw itemError(index, item, ErrorCode.TAG_OBJECT_TYPE_MISMATCH,
                    "标签 " + item.tagCode() + " 不适用于对象类型 " + item.objectType()
                            + "（适用: " + tag.objectType() + "）");
        }
        String valueError = valueValidator.validate(tag, item.tagValue());
        if (valueError != null) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, valueError);
        }
        String normalizedValue = valueValidator.normalize(tag, item.tagValue());
        if (tag.needValueAudit() && isBlank(item.remark())) {
            throw itemError(index, item, ErrorCode.REMARK_REQUIRED,
                    "敏感标签 " + item.tagCode() + " 必须填写打标理由 remark");
        }
        if (item.confidence() != null && !TagValue.CONFIDENCE_LEVELS.contains(item.confidence())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "confidence 仅支持：高/中/低");
        }
        OperatorDto op = item.operator();
        if (op == null || isBlank(op.operatorId())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "operator.operator_id 不能为空");
        }
        if (!OPERATOR_TYPES.contains(op.operatorType())) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, "operator.operator_type 仅支持 human/system/algo");
        }
        checkLength(index, item, "source", item.source(), 64);
        checkLength(index, item, "remark", item.remark(), 2048);
        checkLength(index, item, "evidence_url", item.evidenceUrl(), 512);
        checkLength(index, item, "ext_json", item.extJson(), 2048);
        checkLength(index, item, "operator.operator_id", op.operatorId(), 64);
        checkLength(index, item, "operator.operator_name", op.operatorName(), 128);
        checkLength(index, item, "operator.operator_org", op.operatorOrg(), 128);

        rateLimiter.checkObjectTag(tenantId, item.objectType(), item.objectId(), item.tagCode());

        LocalDateTime operateTime = item.operateTime() != null ? item.operateTime() : now;
        LocalDateTime bizTime = item.bizTime() != null ? item.bizTime() : operateTime;
        LocalDateTime expireTime = tag.valueTtlDays() > 0 ? bizTime.plusDays(tag.valueTtlDays()) : null;
        boolean pendingAudit = review == null && tag.needValueAudit();
        String auditBy = review == null ? null : review.auditBy();
        LocalDateTime auditTime = review == null ? null : now;
        String auditRemark = review == null ? null : review.auditRemark();

        TagValue value = new TagValue(tenantId, item.objectType(), item.objectId(), item.tagCode(),
                normalizedValue, tag.valueType(),
                pendingAudit ? TagValue.STATUS_PENDING_AUDIT : TagValue.STATUS_EFFECTIVE,
                bizTime, operateTime, expireTime,
                op.operatorId(), op.operatorName(), op.operatorType(), op.operatorOrg(),
                !isBlank(item.source()) ? item.source() : "open_api",
                item.confidence(), item.remark(), item.evidenceUrl(),
                auditBy, auditTime, auditRemark,
                requestId, now, System.currentTimeMillis(), item.extJson());
        return new PreparedItem(value, pendingAudit);
    }

    // ----------------------------------------------------------------- delete

    public WriteResult delete(DeleteRequest request, String tenantId, String requestId) {
        String bizRequestId = requireBizRequestId(request.bizRequestId());
        if (!idempotencyService.tryClaim(bizRequestId, tenantId)) {
            return replayOrThrow(bizRequestId);
        }
        try {
            WriteResult result = doDelete(request, tenantId, requestId, bizRequestId);
            idempotencyService.complete(bizRequestId, toJson(result));
            return result;
        } catch (RuntimeException e) {
            idempotencyService.release(bizRequestId);
            throw e;
        }
    }

    private WriteResult doDelete(DeleteRequest request, String tenantId, String requestId, String bizRequestId) {
        List<DeleteRequest.DeleteItem> items = request.items();
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "items 不能为空");
        }
        int batchMax = props.limits().batchMax();
        if (items.size() > batchMax) {
            throw new BizException(ErrorCode.BATCH_EXCEED_LIMIT,
                    "单批最多 " + batchMax + " 条，当前 " + items.size() + " 条");
        }
        Set<String> operatorIds = items.stream()
                .filter(Objects::nonNull)
                .map(DeleteRequest.DeleteItem::operator)
                .filter(Objects::nonNull)
                .map(OperatorDto::operatorId)
                .filter(id -> id != null && !id.isBlank())
                .collect(Collectors.toSet());
        for (String operatorId : operatorIds) {
            rateLimiter.checkOperator(tenantId, operatorId);
        }

        List<WriteResult.ItemResult> results = new ArrayList<>(items.size());
        int revokedCount = 0;
        for (int i = 0; i < items.size(); i++) {
            DeleteRequest.DeleteItem item = items.get(i);
            if (item == null || isBlank(item.objectType()) || isBlank(item.objectId()) || isBlank(item.tagCode())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "第 " + (i + 1) + " 条: object_type/object_id/tag_code 不能为空");
            }
            OperatorDto op = item.operator();
            if (op == null || isBlank(op.operatorId()) || !OPERATOR_TYPES.contains(op.operatorType())) {
                throw new BizException(ErrorCode.PARAM_INVALID, "第 " + (i + 1) + " 条: operator 不完整（operator_id/operator_type）");
            }
            TagMeta tag = tagMetaRepository.findByCode(item.tagCode()).orElse(null);
            if (tag != null && tag.visibleTo(tenantId) && !tag.writable()) {
                throw new BizException(ErrorCode.TAG_WRITE_DISABLED,
                        "第 " + (i + 1) + " 条: 标签 " + item.tagCode() + " 不允许写入（写入开关已关闭），不能作废");
            }
            // latest 由外部程序维护，作废判断只依赖流水，避免外部同步延迟导致误判 not_found
            Optional<TagValue> lastOpt = latestHistory(tenantId, item.objectType(), item.objectId(), item.tagCode());
            if (lastOpt.isEmpty()) {
                results.add(new WriteResult.ItemResult(i, item.objectType(), item.objectId(), item.tagCode(),
                        "not_found", "标签值不存在，无需作废"));
                continue;
            }
            TagValue last = lastOpt.get();
            if (TagValue.STATUS_REVOKED.equals(last.status())) {
                results.add(new WriteResult.ItemResult(i, item.objectType(), item.objectId(), item.tagCode(),
                        "revoked", "标签值已作废（幂等返回）"));
                continue;
            }
            LocalDateTime now = LocalDateTime.now();
            Long newVer = (last.ver() == null ? 1L : last.ver()) + 1;
            TagValue revoked = new TagValue(tenantId, item.objectType(), item.objectId(), item.tagCode(),
                    last.tagValue(), last.valueType(), TagValue.STATUS_REVOKED,
                    last.bizTime(), last.operateTime(), last.expireTime(),
                    op.operatorId(), op.operatorName(), op.operatorType(), op.operatorOrg(),
                    "open_api", last.confidence(),
                    !isBlank(item.remark()) ? item.remark() : last.remark(),
                    last.evidenceUrl(), last.auditBy(), last.auditTime(), last.auditRemark(),
                    requestId, now, newVer, last.extJson());
            // 作废同样只记流水，latest 状态由外部程序同步
            tagValueStore.insertHistory(revoked);
            RequestContext.recordTagDelete(revoked.tagCode());
            RequestContext.setOperatorId(revoked.operatorId());
            RequestContext.setObject(revoked.objectType(), revoked.objectId());
            outboxService.publish("TAG_VALUE_REVOKED", tenantId, eventOf(revoked));
            revokedCount++;
            results.add(new WriteResult.ItemResult(i, item.objectType(), item.objectId(), item.tagCode(),
                    "revoked", null));
        }
        return new WriteResult(bizRequestId, items.size(), revokedCount, 0, results);
    }

    // ------------------------------------------------------------------ 辅助

    /** 该对象 + 标签最近一条流水（按 op_time 倒序取第一条） */
    private Optional<TagValue> latestHistory(String tenantId, String objectType, String objectId, String tagCode) {
        List<TagValue> rows = tagValueStore.queryHistory(new TagValueStore.HistoryQuery(
                tenantId, null, objectType, objectId, tagCode, null, null, 0, 1));
        return rows.stream().findFirst();
    }

    private void checkBatch(List<WriteRequest.WriteItem> items) {
        if (items == null || items.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_MISSING, "items 不能为空");
        }
        int batchMax = props.limits().batchMax();
        if (items.size() > batchMax) {
            throw new BizException(ErrorCode.BATCH_EXCEED_LIMIT,
                    "单批最多 " + batchMax + " 条，当前 " + items.size() + " 条");
        }
    }

    private Map<String, TagMeta> loadTags(List<WriteRequest.WriteItem> items) {
        Set<String> codes = items.stream()
                .filter(Objects::nonNull)
                .map(WriteRequest.WriteItem::tagCode)
                .filter(c -> c != null && !c.isBlank())
                .collect(Collectors.toSet());
        return tagMetaRepository.findByCodes(codes).stream()
                .collect(Collectors.toMap(TagMeta::tagCode, tag -> tag));
    }

    private Map<String, ObjectTypeMeta> loadObjectTypes(List<WriteRequest.WriteItem> items) {
        Set<String> types = items.stream()
                .filter(Objects::nonNull)
                .map(WriteRequest.WriteItem::objectType)
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.toSet());
        Map<String, ObjectTypeMeta> map = new LinkedHashMap<>();
        for (String type : types) {
            objectTypeRepository.findByType(type).ifPresent(meta -> map.put(type, meta));
        }
        return map;
    }

    private WriteResult replayOrThrow(String bizRequestId) {
        return idempotencyService.findCompletedResponse(bizRequestId)
                .map(this::fromJson)
                .orElseThrow(() -> new BizException(ErrorCode.REQUEST_PROCESSING));
    }

    private String toJson(WriteResult result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (Exception e) {
            throw new IllegalStateException("写入响应序列化失败", e);
        }
    }

    private WriteResult fromJson(String json) {
        try {
            return objectMapper.readValue(json, WriteResult.class);
        } catch (Exception e) {
            throw new BizException(ErrorCode.REQUEST_PROCESSING, "幂等记录解析失败，请稍后重试");
        }
    }

    private String requireBizRequestId(String bizRequestId) {
        if (isBlank(bizRequestId) || bizRequestId.length() > 96) {
            throw new BizException(ErrorCode.PARAM_MISSING, "biz_request_id 不能为空且长度不超过 96");
        }
        return bizRequestId;
    }

    private BizException itemError(int index, WriteRequest.WriteItem item, ErrorCode code, String reason) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("index", index);
        if (item != null) {
            data.put("object_type", item.objectType());
            data.put("object_id", item.objectId());
            data.put("tag_code", item.tagCode());
        }
        data.put("reason", reason);
        return new BizException(code, "第 " + (index + 1) + " 条校验失败: " + reason, data);
    }

    private void checkLength(int index, WriteRequest.WriteItem item, String field, String value, int max) {
        if (value != null && value.length() > max) {
            throw itemError(index, item, ErrorCode.PARAM_INVALID, field + " 超长（上限 " + max + "）");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private TagValueEvent eventOf(TagValue v) {
        return new TagValueEvent(v.tenantId(), v.objectType(), v.objectId(), v.tagCode(), v.status(),
                v.tagValue(), v.operatorId(), v.requestId(), v.opTime());
    }

    private record PreparedItem(TagValue value, boolean pendingAudit) {
    }

    private record ReviewInfo(String auditBy, String auditRemark) {
    }

    /** Outbox 事件载荷（V2 投递 Kafka topic tag-value-events） */
    private record TagValueEvent(
            String tenantId,
            String objectType,
            String objectId,
            String tagCode,
            String status,
            String tagValue,
            String operatorId,
            String requestId,
            LocalDateTime opTime) {
    }
}
