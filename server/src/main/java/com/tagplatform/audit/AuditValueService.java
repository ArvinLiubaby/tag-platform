package com.tagplatform.audit;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.write.model.TagValue;
import com.tagplatform.write.store.TagValueStore;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 标签值审核服务（audit 模块）：
 *   标签值审核已停用，tag_value_latest 由外部程序维护；
 *   仅保留待审核队列的只读查询，便于控制台观测。
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
                "标签值审核已停用，tag_value_latest 由外部程序维护");
    }
}
