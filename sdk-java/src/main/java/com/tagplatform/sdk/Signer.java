package com.tagplatform.sdk;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 开放 API 签名器（与平台服务端 SignatureVerifier 完全一致的实现，契约冻结）：
 *   stringToSign = method + "\n" + path + "\n" + canonicalQuery + "\n" + rawBody + "\n" + timestamp + "\n" + nonce
 *   canonicalQuery：参数按 key 字典序，同 key 按 value 字典序，以 "k=v&k=v" 拼接；无参数为空串。
 *   签名算法：HMAC-SHA256，十六进制小写输出。
 */
public final class Signer {

    private static final SecureRandom RANDOM = new SecureRandom();

    private Signer() {
    }

    /** 计算签名 */
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

    /** 规范化查询串：key 字典序 + 同 key value 字典序（值为原值，不做 URL 编码） */
    public static String canonicalQuery(Map<String, List<String>> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((key, values) -> {
            if (values == null) {
                return;
            }
            values.stream().sorted().forEach(value -> {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(key).append('=').append(value);
            });
        });
        return sb.toString();
    }

    /** 实际发送到 URL 的查询串（值与键均做 URL 编码，顺序与 canonicalQuery 一致） */
    public static String encodeQuery(Map<String, List<String>> params) {
        if (params == null || params.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(params).forEach((key, values) -> {
            if (values == null) {
                return;
            }
            values.stream().sorted().forEach(value -> {
                if (sb.length() > 0) {
                    sb.append('&');
                }
                sb.append(urlEncode(key)).append('=').append(urlEncode(value));
            });
        });
        return sb.toString();
    }

    /** 生成随机 nonce（16 字节 hex） */
    public static String newNonce() {
        byte[] buffer = new byte[16];
        RANDOM.nextBytes(buffer);
        return HexFormat.of().formatHex(buffer);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
