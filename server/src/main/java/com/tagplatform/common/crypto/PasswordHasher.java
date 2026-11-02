package com.tagplatform.common.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

/**
 * 控制台用户口令散列（PBKDF2-HMAC-SHA256）：
 * 存储格式 pbkdf2:{iterations}:{saltB64}:{hashB64}；
 * 兼容种子数据 plain:{明文}，首次登录校验通过后可渐进升级为 pbkdf2 格式。
 */
public class PasswordHasher {

    public static final String PLAIN_PREFIX = "plain:";
    public static final String PBKDF2_PREFIX = "pbkdf2:";
    private static final int ITERATIONS = 120_000;
    private static final int KEY_BITS = 256;
    private static final int SALT_BYTES = 16;

    private final SecureRandom random = new SecureRandom();

    /** 生成 pbkdf2 格式散列 */
    public String hash(String rawPassword) {
        byte[] salt = new byte[SALT_BYTES];
        random.nextBytes(salt);
        byte[] hash = pbkdf2(rawPassword.toCharArray(), salt, ITERATIONS);
        return PBKDF2_PREFIX + ITERATIONS + ":" + Base64.getEncoder().encodeToString(salt)
                + ":" + Base64.getEncoder().encodeToString(hash);
    }

    /** 校验口令；常量时间比较防时序侧信道 */
    public boolean verify(String rawPassword, String stored) {
        if (rawPassword == null || stored == null) {
            return false;
        }
        if (stored.startsWith(PLAIN_PREFIX)) {
            return MessageDigest.isEqual(
                    rawPassword.getBytes(StandardCharsets.UTF_8),
                    stored.substring(PLAIN_PREFIX.length()).getBytes(StandardCharsets.UTF_8));
        }
        if (stored.startsWith(PBKDF2_PREFIX)) {
            String[] parts = stored.substring(PBKDF2_PREFIX.length()).split(":");
            if (parts.length != 3) {
                return false;
            }
            try {
                byte[] salt = Base64.getDecoder().decode(parts[1]);
                byte[] expect = Base64.getDecoder().decode(parts[2]);
                byte[] actual = pbkdf2(rawPassword.toCharArray(), salt, Integer.parseInt(parts[0]));
                return MessageDigest.isEqual(expect, actual);
            } catch (IllegalArgumentException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * 旧格式（plain:）渐进升级：校验通过时返回 pbkdf2 散列供落库，否则返回 null。
     */
    public String upgradeIfLegacy(String rawPassword, String stored) {
        if (stored != null && stored.startsWith(PLAIN_PREFIX) && verify(rawPassword, stored)) {
            return hash(rawPassword);
        }
        return null;
    }

    private byte[] pbkdf2(char[] password, byte[] salt, int iterations) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, KEY_BITS);
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded();
        } catch (Exception e) {
            throw new IllegalStateException("口令散列计算失败", e);
        }
    }
}
