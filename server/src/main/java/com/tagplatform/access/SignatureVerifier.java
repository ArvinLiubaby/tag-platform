package com.tagplatform.access;

import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.config.AppProperties;
import com.tagplatform.meta.model.CredentialInfo;
import com.tagplatform.meta.service.CredentialService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 开放 API 签名验签（HMAC-SHA256）：
 * 头部：X-App-Key / X-Timestamp(毫秒) / X-Nonce / X-Signature(hex)
 * stringToSign = method + "\n" + path + "\n" + canonicalQuery + "\n" + rawBody + "\n" + timestamp + "\n" + nonce
 * canonicalQuery：参数按 key 字典序，同 key 按 value 字典序，以解码后的原值 "k=v&k=v" 拼接；无参数为空串。
 * 校验顺序：头部完整性 → 时间窗(±skew) → 凭证/租户状态 → 签名 → Nonce 防重放。
 * 验签通过后写入请求上下文（appKey/tenantId），全链路透传。
 */
@Component
public class SignatureVerifier {

    public static final String HEADER_APP_KEY = "X-App-Key";
    public static final String HEADER_TIMESTAMP = "X-Timestamp";
    public static final String HEADER_NONCE = "X-Nonce";
    public static final String HEADER_SIGNATURE = "X-Signature";

    private final CredentialService credentialService;
    private final KeyValueStore kv;
    private final AppProperties props;

    public SignatureVerifier(CredentialService credentialService, KeyValueStore kv, AppProperties props) {
        this.credentialService = credentialService;
        this.kv = kv;
        this.props = props;
    }

    public void verify(HttpServletRequest request, byte[] body) {
        String appKey = trimToNull(request.getHeader(HEADER_APP_KEY));
        String timestamp = trimToNull(request.getHeader(HEADER_TIMESTAMP));
        String nonce = trimToNull(request.getHeader(HEADER_NONCE));
        String signature = trimToNull(request.getHeader(HEADER_SIGNATURE));
        if (appKey == null || timestamp == null || nonce == null || signature == null) {
            throw new BizException(ErrorCode.INVALID_AUTH_HEADER);
        }

        long tsMillis;
        try {
            tsMillis = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            throw new BizException(ErrorCode.INVALID_AUTH_HEADER, "X-Timestamp 必须为毫秒级 Unix 时间戳");
        }
        long skewMillis = props.limits().timestampSkewSeconds() * 1000L;
        if (Math.abs(System.currentTimeMillis() - tsMillis) > skewMillis) {
            throw new BizException(ErrorCode.TIMESTAMP_EXPIRED);
        }

        CredentialInfo credential = credentialService.findByAppKey(appKey)
                .orElseThrow(() -> new BizException(ErrorCode.APP_KEY_NOT_FOUND));
        if (!credential.credentialEnabled()) {
            throw new BizException(ErrorCode.CREDENTIAL_DISABLED);
        }
        if (!credential.tenantEnabled()) {
            throw new BizException(ErrorCode.TENANT_DISABLED);
        }

        String expected = sign(request.getMethod(), request.getRequestURI(), canonicalQuery(request),
                new String(body, StandardCharsets.UTF_8), timestamp, nonce, credential.secret());
        if (!MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                signature.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            throw new BizException(ErrorCode.SIGNATURE_MISMATCH);
        }

        // 防重放：验签通过后登记 nonce（首次出现才放行）
        boolean firstSeen = kv.setIfAbsent("open:nonce:" + appKey + ":" + nonce, "1",
                Duration.ofSeconds(props.limits().nonceTtlSeconds()));
        if (!firstSeen) {
            throw new BizException(ErrorCode.NONCE_REPLAY);
        }

        RequestContext.setAppKey(appKey);
        RequestContext.setTenantId(credential.tenantId());
    }

    /** 签名算法（SDK 端与冒烟脚本必须与此完全一致） */
    public static String sign(String method, String path, String canonicalQuery, String rawBody,
                              String timestamp, String nonce, String secret) {
        String stringToSign = method + "\n" + path + "\n" + canonicalQuery + "\n" + rawBody + "\n"
                + timestamp + "\n" + nonce;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(stringToSign.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("签名计算失败", e);
        }
    }

    /** 规范化查询串：key 字典序 + 同 key value 字典序（值为解码后原值） */
    public static String canonicalQuery(HttpServletRequest request) {
        Map<String, String[]> params = request.getParameterMap();
        if (params.isEmpty()) {
            return "";
        }
        return params.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(e -> Arrays.stream(e.getValue()).sorted().map(v -> e.getKey() + "=" + v))
                .collect(Collectors.joining("&"));
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
