package com.tagplatform.access.kv;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class InMemoryKeyValueStoreTest {

    private final InMemoryKeyValueStore kv = new InMemoryKeyValueStore();

    @Test
    void setAndGet() {
        kv.set("k", "v", Duration.ofSeconds(60));
        assertThat(kv.get("k")).contains("v");
    }

    @Test
    void expiredKeyInvisible() throws InterruptedException {
        kv.set("k", "v", Duration.ofMillis(30));
        Thread.sleep(60);
        assertThat(kv.get("k")).isEmpty();
    }

    @Test
    void setIfAbsentFirstTrueThenFalse() {
        assertThat(kv.setIfAbsent("k", "1", Duration.ofSeconds(60))).isTrue();
        assertThat(kv.setIfAbsent("k", "1", Duration.ofSeconds(60))).isFalse();
    }

    @Test
    void setIfAbsentSameMillisecondReplayRejected() {
        // 回归测试：同一毫秒内、相同 value/expireAt 的重放请求必须被拒绝（防重放 nonce 场景）
        for (int i = 0; i < 50; i++) {
            String key = "nonce:" + i;
            assertThat(kv.setIfAbsent(key, "1", Duration.ofSeconds(60))).isTrue();
            assertThat(kv.setIfAbsent(key, "1", Duration.ofSeconds(60))).isFalse();
        }
    }

    @Test
    void setIfAbsentAfterExpireTrue() throws InterruptedException {
        assertThat(kv.setIfAbsent("k", "1", Duration.ofMillis(30))).isTrue();
        Thread.sleep(60);
        assertThat(kv.setIfAbsent("k", "1", Duration.ofSeconds(60))).isTrue();
    }

    @Test
    void deleteRemovesKey() {
        kv.set("k", "v", Duration.ofSeconds(60));
        kv.delete("k");
        assertThat(kv.get("k")).isEmpty();
    }

    @Test
    void incrementCountsWithinTtl() {
        assertThat(kv.increment("counter", Duration.ofSeconds(60))).isEqualTo(1L);
        assertThat(kv.increment("counter", Duration.ofSeconds(60))).isEqualTo(2L);
        assertThat(kv.increment("counter", Duration.ofSeconds(60))).isEqualTo(3L);
    }

    @Test
    void incrementResetsAfterExpire() throws InterruptedException {
        assertThat(kv.increment("counter", Duration.ofMillis(30))).isEqualTo(1L);
        Thread.sleep(60);
        assertThat(kv.increment("counter", Duration.ofSeconds(60))).isEqualTo(1L);
    }
}
