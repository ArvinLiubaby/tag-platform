package com.tagplatform.console.model;

/**
 * 控制台登录会话（KV 存储 JSON 形态，TTL 见 tag-platform.console.token-ttl-hours）。
 * authVersion 用于强制下线：改密 / 停用 / 角色变更后递增，旧会话立即失效。
 */
public record ConsoleSession(
        String username,
        String displayName,
        String role,
        String tenantId,
        String operatorId,
        String operatorName,
        String operatorOrg,
        String team,
        Long authVersion) {

    public long version() {
        return authVersion == null ? 0L : authVersion;
    }
}
