package com.tagplatform.common.crypto;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * 凭证 Secret 的可逆加密（AES-256-GCM）：
 * 存储格式 enc:v1:{base64(iv(12) || ciphertext+tag)}；
 * 非 enc:v1: 前缀视为明文（兼容 Flyway 种子数据），读取时原样返回。
 */
public class AesCipher {

    public static final String ENC_PREFIX = "enc:v1:";
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final SecretKeySpec key;
    private final SecureRandom random = new SecureRandom();

    public AesCipher(String masterKey) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(masterKey.getBytes(StandardCharsets.UTF_8));
            this.key = new SecretKeySpec(digest, "AES");
        } catch (Exception e) {
            throw new IllegalStateException("初始化加密密钥失败", e);
        }
    }

    /** 加密明文，返回带 enc:v1: 前缀的密文 */
    public String encrypt(String plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] ct = cipher.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return ENC_PREFIX + Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("加密失败", e);
        }
    }

    /** 带 enc:v1: 前缀的值解密；否则原样返回（种子明文兼容） */
    public String decryptIfNeeded(String value) {
        if (value == null || !value.startsWith(ENC_PREFIX)) {
            return value;
        }
        try {
            byte[] raw = Base64.getDecoder().decode(value.substring(ENC_PREFIX.length()));
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, raw, 0, IV_LENGTH));
            byte[] pt = cipher.doFinal(raw, IV_LENGTH, raw.length - IV_LENGTH);
            return new String(pt, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("解密失败，请检查 TAG_PLATFORM_MASTER_KEY 是否与加密时一致", e);
        }
    }

    /** 控制台写入凭证时调用：明文也加密落库；已是密文则不变 */
    public String encryptIfNeeded(String value) {
        if (value == null || value.startsWith(ENC_PREFIX)) {
            return value;
        }
        return encrypt(value);
    }
}
