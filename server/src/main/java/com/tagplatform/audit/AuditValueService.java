package com.tagplatform.audit;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签值审核服务（audit 模块）：
 *   待审核队列代表打标过程中的审核环节，数据源为打标流水（tag_value_history）中
 *   status=pending_audit 的敏感标签值；tag_value_latest 由外部程序综合计算维护，
 *   不用于审核判定。
 *   审核动作（approve/reject）已停用，仅保留只读查询，界面为后续审核功能扩展预留。
 */
@Service
public class AuditValueService {

    private final TagValueStore tagValueStore;

    public AuditValueService(TagValueStore tagValueStore) {
        this.tagValueStore = tagValueStore;
    }

    public List<TagValue> pending(int limit) {
        int normalized = Math.min(Math.max(limit, 1), 500);
        return tagValueStore.listPendingAudit(normalized);
    }

    public List<TagValue> pending(String tenantId, int limit) {
        int normalized = Math.min(Math.max(limit, 1), 500);
        if (tenantId == null || tenantId.isBlank()) {
            return tagValueStore.listPendingAudit(normalized);
        }
        return tagValueStore.listPendingAudit(tenantId, normalized);
    }

    public TagValue approve(String tenantId, String objectType, String objectId, String tagCode,
                            String auditor, String auditRemark) {
        throw auditDisabled();
    }

    public TagValue reject(String tenantId, String objectType, String objectId, String tagCode,
                           String auditor, String auditRemark) {
        throw auditDisabled();
    }

    private BizException auditDisabled() {
        return new BizException(ErrorCode.AUDIT_DISABLED,
                "标签值审核已停用，本界面为后续审核功能扩展预留");
    }
}
