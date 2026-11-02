package com.tagplatform.common.crypto;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PasswordHasherTest {

    private final PasswordHasher hasher = new PasswordHasher();

    @Test
    void hashAndVerify() {
        String stored = hasher.hash("admin123");
        assertThat(stored).startsWith(PasswordHasher.PBKDF2_PREFIX);
        assertThat(hasher.verify("admin123", stored)).isTrue();
        assertThat(hasher.verify("wrong", stored)).isFalse();
    }

    @Test
    void hashIsSalted() {
        assertThat(hasher.hash("admin123")).isNotEqualTo(hasher.hash("admin123"));
    }

    @Test
    void plainSeedCompatibility() {
        // 种子数据 plain: 前缀兼容
        assertThat(hasher.verify("admin123", "plain:admin123")).isTrue();
        assertThat(hasher.verify("admin123", "plain:other")).isFalse();
    }

    @Test
    void upgradeIfLegacyOnlyForPlainAndVerified() {
        assertThat(hasher.upgradeIfLegacy("admin123", "plain:admin123"))
                .startsWith(PasswordHasher.PBKDF2_PREFIX);
        assertThat(hasher.upgradeIfLegacy("admin123", "plain:other")).isNull();
        assertThat(hasher.upgradeIfLegacy("admin123", hasher.hash("admin123"))).isNull();
    }

    @Test
    void malformedStoredValueRejected() {
        assertThat(hasher.verify("x", "pbkdf2:broken")).isFalse();
        assertThat(hasher.verify("x", "unknown-format")).isFalse();
        assertThat(hasher.verify(null, "plain:x")).isFalse();
        assertThat(hasher.verify("x", null)).isFalse();
    }
}
