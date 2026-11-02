package com.tagplatform.meta.service;

import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.crypto.AesCipher;
import com.tagplatform.meta.model.AppCredentialInfo;
import com.tagplatform.meta.model.CredentialInfo;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;

/**
 * 凭证查询服务：开放 API 鉴权链路入口（Secret 读取时解密；种子明文兼容）。
 */
@Service
public class CredentialService {

    private static final String FIND_BY_APP_KEY_SQL = """
            SELECT c.app_key, c.secret_enc, c.status AS cred_status, c.tenant_id, t.status AS tenant_status
            FROM app_credential c
            JOIN tenant t ON c.tenant_id = t.tenant_id
            WHERE c.app_key = ?
            """;

    private static final RowMapper<AppCredentialInfo> INFO_MAPPER = (rs, rowNum) -> new AppCredentialInfo(
            rs.getLong("id"),
            rs.getString("tenant_id"),
            rs.getString("app_key"),
            rs.getString("status"),
            rs.getTimestamp("created_at").toLocalDateTime());

    private final JdbcTemplate jdbcTemplate;
    private final AesCipher aesCipher;
    private final SecureRandom secureRandom = new SecureRandom();

    public CredentialService(JdbcTemplate jdbcTemplate, AesCipher aesCipher) {
        this.jdbcTemplate = jdbcTemplate;
        this.aesCipher = aesCipher;
    }

    public Optional<CredentialInfo> findByAppKey(String appKey) {
        List<CredentialInfo> rows = jdbcTemplate.query(FIND_BY_APP_KEY_SQL, (rs, rowNum) -> new CredentialInfo(
                rs.getString("app_key"),
                aesCipher.decryptIfNeeded(rs.getString("secret_enc")),
                rs.getString("tenant_id"),
                rs.getString("cred_status"),
                rs.getString("tenant_status")), appKey);
        return rows.stream().findFirst();
    }

    public List<AppCredentialInfo> listByTenant(String tenantId) {
        if (tenantId == null || tenantId.isBlank()) {
            return jdbcTemplate.query(
                    "SELECT id, tenant_id, app_key, status, created_at FROM app_credential ORDER BY created_at",
                    INFO_MAPPER);
        }
        return jdbcTemplate.query(
                "SELECT id, tenant_id, app_key, status, created_at FROM app_credential WHERE tenant_id = ? ORDER BY created_at",
                INFO_MAPPER, tenantId);
    }

    /** 新建凭证：AppKey 自动生成，Secret 仅返回一次（落库为密文） */
    @Transactional
    public CredentialSecret create(String tenantId) {
        String appKey = "AK_" + tenantId.replaceAll("[^A-Za-z0-9]", "").toUpperCase() + "_" + randomHex(3);
        String secret = randomHex(16);
        jdbcTemplate.update(
                "INSERT INTO app_credential (tenant_id, app_key, secret_enc, status, created_at) VALUES (?, ?, ?, 'enabled', ?)",
                tenantId, appKey, aesCipher.encrypt(secret), LocalDateTime.now());
        return new CredentialSecret(appKey, secret);
    }

    /** 重置密钥：旧 Secret 立即失效，新 Secret 仅返回一次 */
    @Transactional
    public CredentialSecret resetSecret(String appKey) {
        String secret = randomHex(16);
        int updated = jdbcTemplate.update("UPDATE app_credential SET secret_enc = ? WHERE app_key = ?",
                aesCipher.encrypt(secret), appKey);
        if (updated == 0) {
            throw new BizException(ErrorCode.APP_KEY_NOT_FOUND, "AppKey 不存在: " + appKey);
        }
        return new CredentialSecret(appKey, secret);
    }

    public int updateStatus(String appKey, String status) {
        return jdbcTemplate.update("UPDATE app_credential SET status = ? WHERE app_key = ?", status, appKey);
    }

    private String randomHex(int bytes) {
        byte[] buffer = new byte[bytes];
        secureRandom.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    /** 一次性明文凭证（新建/重置响应） */
    public record CredentialSecret(String appKey, String secret) {
    }
}
