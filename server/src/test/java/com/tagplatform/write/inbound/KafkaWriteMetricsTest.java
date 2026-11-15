package com.tagplatform.write.inbound;

import com.tagplatform.access.kv.InMemoryKeyValueStore;
import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.config.AppProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Kafka 通道计数：本次（进程内）与累计（KV 持久化）两套口径。
 * 用内存 KV 模拟 Redis，重点验证「重启后本次清零、累计保留」。
 */
class KafkaWriteMetricsTest {

    private static AppProperties props() {
        return new AppProperties(null, "redis", "starrocks", null, null, null,
                new AppProperties.Kafka(true, "localhost:9092", "profile_tag_write", "tag-platform-write",
                        "profile_tag_write_dlq", "risk_control", 1, 50));
    }

    @Test
    void 累计算法随消息分类分别累加() {
        KafkaWriteMetrics metrics = new KafkaWriteMetrics(new InMemoryKeyValueStore(), props());

        metrics.onReceived();
        metrics.onSuccess(false);
        metrics.onReceived();
        metrics.onSuccess(true);
        metrics.onReceived();
        metrics.onFailed("业务校验失败");
        metrics.onRetry("首次请求仍在处理");

        KafkaWriteMetrics.Totals totals = metrics.totals();
        assertThat(totals.received()).isEqualTo(3);
        assertThat(totals.succeeded()).isEqualTo(1);
        assertThat(totals.duplicates()).isEqualTo(1);
        assertThat(totals.failed()).isEqualTo(1);
        assertThat(totals.retried()).isEqualTo(1);
        assertThat(totals.since()).isNotBlank();
    }

    @Test
    void 服务重启后本次清零累计保留() {
        InMemoryKeyValueStore kv = new InMemoryKeyValueStore();

        KafkaWriteMetrics first = new KafkaWriteMetrics(kv, props());
        first.onReceived();
        first.onSuccess(false);
        first.onReceived();
        first.onSuccess(false);
        first.onReceived();
        first.onFailed("items 为空");

        // 模拟重启：同一个 KV，新的指标实例（进程内计数归零）
        KafkaWriteMetrics restarted = new KafkaWriteMetrics(kv, props());

        assertThat(restarted.received()).isZero();
        assertThat(restarted.succeeded()).isZero();
        assertThat(restarted.totals().received()).isEqualTo(3);
        assertThat(restarted.totals().succeeded()).isEqualTo(2);
        assertThat(restarted.totals().failed()).isEqualTo(1);
    }

    @Test
    void 首次接入时间只在首次写入时确定() {
        InMemoryKeyValueStore kv = new InMemoryKeyValueStore();
        String since = new KafkaWriteMetrics(kv, props()).totals().since();
        assertThat(since).isNotBlank();

        String again = new KafkaWriteMetrics(kv, props()).totals().since();
        assertThat(again).isEqualTo(since);
    }

    @Test
    void KV不可用时只降级累计且不影响本次计数() {
        KeyValueStore broken = new KeyValueStore() {
            @Override
            public Optional<String> get(String key) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void set(String key, String value, Duration ttl) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean setIfAbsent(String key, String value, Duration ttl) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public void delete(String key) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public long increment(String key, Duration ttl) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public Optional<Duration> remainingTtl(String key) {
                throw new IllegalStateException("redis down");
            }

            @Override
            public boolean expire(String key, Duration ttl) {
                throw new IllegalStateException("redis down");
            }
        };

        KafkaWriteMetrics metrics = new KafkaWriteMetrics(broken, props());
        assertThatCode(() -> {
            metrics.onReceived();
            metrics.onSuccess(false);
        }).doesNotThrowAnyException();

        assertThat(metrics.received()).isEqualTo(1);
        assertThat(metrics.succeeded()).isEqualTo(1);
        assertThat(metrics.totals().received()).isZero();
        assertThat(metrics.totals().since()).isNull();
    }
}
