package com.tagplatform.write.web.dto;

import java.util.List;

/**
 * 写入/作废统一响应（契约冻结：字段只增不删）。
 * status 取值：accepted（已生效）/ pending_audit（待审核）/ revoked（已作废）/ not_found（不存在）。
 */
public record WriteResult(
        String bizRequestId,
        int total,
        int accepted,
        int pendingAudit,
        List<ItemResult> results) {

    public record ItemResult(
            int index,
            String objectType,
            String objectId,
            String tagCode,
            String status,
            String message) {
    }
}
