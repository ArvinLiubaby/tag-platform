package com.tagplatform.meta.model;

import java.time.LocalDateTime;

/**
 * 应用凭证视图（不含 Secret，控制台安全展示）。
 */
public record AppCredentialInfo(
        Long id,
        String tenantId,
        String appKey,
        String status,
        LocalDateTime createdAt) {

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }
}
