package com.tagplatform.console.model;

import java.time.LocalDateTime;

/**
 * 控制台用户（console_user 表映射）。
 */
public record ConsoleUser(
        Long id,
        String username,
        String passwordHash,
        String displayName,
        String userRole,
        String status,
        String tenantId,
        String operatorId,
        String operatorName,
        String operatorOrg,
        String team,
        LocalDateTime createdAt) {

    public boolean enabled() {
        return "enabled".equalsIgnoreCase(status);
    }
}
