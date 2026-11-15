package com.tagplatform.write.inbound;

import com.tagplatform.access.kv.KeyValueStore;
import com.tagplatform.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka 写入通道运行计数。
 * <p>定位是「接入健康看板」而非运维监控：只回答「有没有数据进来、成了多少、错了多少、最后一次是什么时候」。</p>
 *
 * <p>两套口径：</p>
 * <ul>
 *   <li><b>本次</b>：进程内 {@link AtomicLong}，随服务重启清零，用来判断「这一次启动后通道通不通」；</li>
 *   <li><b>累计</b>：同口径计数同步累加到 KV（生产 Redis），服务重启不清零，用来判断「这条通道历史上到底跑过多少」。
 *       键为 {@code tp:kafka:stat:{groupId}:*}，保留 10 年，等价于长期保留。</li>
 * </ul>
 *
 * <p>累计计数是旁路统计：KV 异常时只降级为「本次」可用，绝不因为计数失败影响消费。
 * 失败后 30 秒内跳过累加（熔断），避免 Redis 抖动时每条消息都等一次超时。</p>
 */
@Component
@ConditionalOnProperty(prefix = "tag-platform.kafka", name = "enabled", havingValue = "true")
public class KafkaWriteMetrics {

    private static final Logger log = LoggerFactory.getLogger(KafkaWriteMetrics.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 累计计数保留期：10 年（等价永久，但避免 Redis 里出现永不过期的键） */
    private static final Duration TOTAL_TTL = Duration.ofDays(3650);
    /** KV 失败熔断窗口 */
    private static final long KV_BLOCK_MILLIS = 30_000L;

    private final KeyValueStore kv;
    private final String prefix;
    private final LocalDateTime startedAt = LocalDateTime.now();

    private final AtomicLong received = new AtomicLong();
    private final AtomicLong succeeded = new AtomicLong();
    private final AtomicLong duplicates = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong retried = new AtomicLong();

    private volatile LocalDateTime lastConsumedAt;
    private volatile String lastError;
    private volatile LocalDateTime lastErrorAt;

    private volatile long kvBlockedUntil;
    private volatile boolean kvWarned;

    public KafkaWriteMetrics(KeyValueStore kv, AppProperties props) {
        this.kv = kv;
        String group = (props == null || props.kafka() == null || props.kafka().groupId() == null)
                ? "default" : props.kafka().groupId();
        this.prefix = "tp:kafka:stat:" + group + ":";
        ensureSince();
    }

    public void onReceived() {
        received.incrementAndGet();
        incr("received");
    }

    /** @param replayed true = 幂等重放（重复消息），不计入首次成功 */
    public void onSuccess(boolean replayed) {
        if (replayed) {
            duplicates.incrementAndGet();
            incr("duplicates");
        } else {
            succeeded.incrementAndGet();
            incr("succeeded");
        }
        lastConsumedAt = LocalDateTime.now();
    }

    public void onFailed(String reason) {
        failed.incrementAndGet();
        incr("failed");
        lastError = reason;
        lastErrorAt = LocalDateTime.now();
        lastConsumedAt = LocalDateTime.now();
    }

    public void onRetry(String reason) {
        retried.incrementAndGet();
        incr("retried");
        lastError = reason;
        lastErrorAt = LocalDateTime.now();
    }

    /* ---------------- 本次（进程内，重启清零） ---------------- */

    public LocalDateTime startedAt() {
        return startedAt;
    }

    public long received() {
        return received.get();
    }

    public long succeeded() {
        return succeeded.get();
    }

    public long duplicates() {
        return duplicates.get();
    }

    public long failed() {
        return failed.get();
    }

    public long retried() {
        return retried.get();
    }

    public LocalDateTime lastConsumedAt() {
        return lastConsumedAt;
    }

    public String lastError() {
        return lastError;
    }

    public LocalDateTime lastErrorAt() {
        return lastErrorAt;
    }

    /* ---------------- 累计（KV 持久化，跨重启） ---------------- */

    /** 累计计数快照；KV 不可用时各项返回 0、since 为 null */
    public record Totals(long received, long succeeded, long duplicates, long failed, long retried, String since) {
    }

    public Totals totals() {
        return new Totals(read("received"), read("succeeded"), read("duplicates"),
                read("failed"), read("retried"), readSince());
    }

    private String readSince() {
        try {
            return kv.get(prefix + "since").orElse(null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private long read(String name) {
        try {
            return kv.get(prefix + name).map(Long::parseLong).orElse(0L);
        } catch (RuntimeException e) {
            return 0L;
        }
    }

    /** 首次接入时间：只在键不存在时写入，因此反映「这条通道第一次被统计到」的时刻 */
    private void ensureSince() {
        try {
            kv.setIfAbsent(prefix + "since", LocalDateTime.now().format(TS), TOTAL_TTL);
        } catch (RuntimeException e) {
            log.debug("累计计数初始化失败（不影响本次计数）: {}", e.toString());
        }
    }

    private void incr(String name) {
        long now = System.currentTimeMillis();
        if (now < kvBlockedUntil) {
            return;
        }
        try {
            kv.increment(prefix + name, TOTAL_TTL);
            kvWarned = false;
        } catch (RuntimeException e) {
            kvBlockedUntil = now + KV_BLOCK_MILLIS;
            if (!kvWarned) {
                kvWarned = true;
                log.warn("累计计数写入失败，{}s 内跳过累加（消费不受影响）: {}",
                        KV_BLOCK_MILLIS / 1000, e.toString());
            }
        }
    }
}
