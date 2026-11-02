package com.tagplatform.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AesCipherTest {

    private final AesCipher cipher = new AesCipher("unit-test-master-key");

    @Test
    void encryptDecryptRoundTrip() {
        String secret = "rc-secret-8f2a91-demo";
        String encrypted = cipher.encrypt(secret);
        assertThat(encrypted).startsWith(AesCipher.ENC_PREFIX);
        assertThat(encrypted).doesNotContain(secret);
        assertThat(cipher.decryptIfNeeded(encrypted)).isEqualTo(secret);
    }

    @Test
    void samePlaintextEncryptsToDifferentCiphertext() {
        // 随机 IV：相同明文两次加密密文不同
        assertThat(cipher.encrypt("abc")).isNotEqualTo(cipher.encrypt("abc"));
    }

    @Test
    void plainValuePassThrough() {
        // Flyway 种子明文无 enc:v1: 前缀，读取时原样返回
        assertThat(cipher.decryptIfNeeded("plain-secret")).isEqualTo("plain-secret");
        assertThat(cipher.decryptIfNeeded(null)).isNull();
    }

    @Test
    void encryptIfNeededIsIdempotent() {
        String once = cipher.encryptIfNeeded("abc");
        assertThat(cipher.encryptIfNeeded(once)).isEqualTo(once);
    }

    @Test
    void tamperedCiphertextRejected() {
        String encrypted = cipher.encrypt("secret");
        char last = encrypted.charAt(encrypted.length() - 1);
        String tampered = encrypted.substring(0, encrypted.length() - 1) + (last == 'A' ? 'B' : 'A');
        assertThatThrownBy(() -> cipher.decryptIfNeeded(tampered)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void wrongMasterKeyRejected() {
        String encrypted = new AesCipher("key-a").encrypt("secret");
        AesCipher other = new AesCipher("key-b");
        assertThatThrownBy(() -> other.decryptIfNeeded(encrypted)).isInstanceOf(IllegalStateException.class);
    }
}
