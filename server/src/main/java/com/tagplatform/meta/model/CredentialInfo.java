package com.tagplatform.meta.model;

/**
 * 开放 API 凭证视图（secret 已解密；携带租户状态用于快速拒绝）。
 */
public record CredentialInfo(
        String appKey,
        String secret,
        String tenantId,
        String credentialStatus,
        String tenantStatus) {

    public boolean credentialEnabled() {
        return "enabled".equalsIgnoreCase(credentialStatus);
    }

    public boolean tenantEnabled() {
        return "enabled".equalsIgnoreCase(tenantStatus);
    }
}
