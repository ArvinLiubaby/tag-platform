package com.tagplatform.meta.model;

import java.time.LocalDateTime;

/**
 * 租户信息（tenant 表映射，控制台展示用）。
 */
public record TenantInfo(
        Long id,
        String tenantId,
        String tenantName,
        String accessSystem,
        String status,
        int writeQps,
        int readQps,
        LocalDateTime createdAt,
        LocalDateTime updatedAt) {

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }
}
