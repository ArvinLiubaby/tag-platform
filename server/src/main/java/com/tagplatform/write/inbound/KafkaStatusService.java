package com.tagplatform.write.inbound;

import com.tagplatform.config.AppProperties;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ConsumerGroupDescription;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Kafka 接入健康状态：topic 分区数、未消费积压、消费组状态、死信条数 + 进程内消费计数。
 *
 * <p>定位是「接入看板」，不是运维监控：只回答「数据有没有进来 / 卡没卡 / 错了多少」。
 * broker 指标（ISR、磁盘、网络）应交给 kafka_exporter + Grafana。</p>
 *
 * <p>offset 查询会连 broker，结果缓存 30 秒，避免页面反复刷新打爆集群。</p>
 */
@Service
@ConditionalOnProperty(prefix = "tag-platform.kafka", name = "enabled", havingValue = "true")
public class KafkaStatusService {

    private static final Logger log = LoggerFactory.getLogger(KafkaStatusService.class);
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long CACHE_MILLIS = 30_000L;

    private final AppProperties props;
    private final KafkaWriteMetrics metrics;

    private volatile Snapshot cache;
    private volatile long cacheAt;

    public KafkaStatusService(AppProperties props, KafkaWriteMetrics metrics) {
        this.props = props;
        this.metrics = metrics;
    }

    /** broker 侧快照（30 秒缓存） */
    private record Snapshot(int partitions, String groupState, long lag, long deadLetters, String error) {
    }

    /** 看板返回结构（Jackson 全局 SNAKE_CASE → 前端 snake_case） */
    public record Status(boolean enabled,
                         String topic,
                         String group,
                         int partitions,
                         String groupState,
                         long lag,
                         long deadLetters,
                         long received,
                         long succeeded,
                         long duplicates,
                         long failed,
                         long retried,
                         long totalReceived,
                         long totalSucceeded,
                         long totalDuplicates,
                         long totalFailed,
                         long totalRetried,
                         String totalSince,
                         String lastConsumedAt,
                         String lastError,
                         String lastErrorAt,
                         String startedAt,
                         String snapshotError) {
    }

    public Status status() {
        Snapshot snap = snapshot();
        KafkaWriteMetrics.Totals totals = metrics.totals();
        return new Status(true,
                props.kafka().topic(),
                props.kafka().groupId(),
                snap.partitions(),
                snap.groupState(),
                snap.lag(),
                snap.deadLetters(),
                metrics.received(),
                metrics.succeeded(),
                metrics.duplicates(),
                metrics.failed(),
                metrics.retried(),
                totals.received(),
                totals.succeeded(),
                totals.duplicates(),
                totals.failed(),
                totals.retried(),
                totals.since(),
                fmt(metrics.lastConsumedAt()),
                metrics.lastError(),
                fmt(metrics.lastErrorAt()),
                fmt(metrics.startedAt()),
                snap.error());
    }

    private static String fmt(LocalDateTime time) {
        return time == null ? null : time.format(TS);
    }

    private Snapshot snapshot() {
        Snapshot current = cache;
        if (current != null && System.currentTimeMillis() - cacheAt < CACHE_MILLIS) {
            return current;
        }
        synchronized (this) {
            if (cache != null && System.currentTimeMillis() - cacheAt < CACHE_MILLIS) {
                return cache;
            }
            Snapshot fresh = query();
            cache = fresh;
            cacheAt = System.currentTimeMillis();
            return fresh;
        }
    }

    private Snapshot query() {
        String topic = props.kafka().topic();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps())) {
            List<PartitionInfo> parts = consumer.partitionsFor(topic);
            if (parts == null || parts.isEmpty()) {
                return new Snapshot(0, groupState(), 0, deadLetterCount(consumer), "topic 不存在或暂无分区");
            }
            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo pi : parts) {
                tps.add(new TopicPartition(topic, pi.partition()));
            }
            Map<TopicPartition, Long> end = consumer.endOffsets(tps);
            long lag = 0;
            for (TopicPartition tp : tps) {
                long tail = end.getOrDefault(tp, 0L);
                OffsetAndMetadata committed = null;
                try {
                    committed = consumer.committed(tp);
                } catch (RuntimeException ignore) {
                    // 无位点（尚未消费过）按「全部未消费」计
                }
                lag += (committed == null) ? tail : Math.max(0L, tail - committed.offset());
            }
            return new Snapshot(tps.size(), groupState(), lag, deadLetterCount(consumer), null);
        } catch (RuntimeException e) {
            log.warn("查询 Kafka 接入状态失败 topic={}: {}", topic, e.toString());
            return new Snapshot(0, "UNKNOWN", 0, 0, "查询失败: " + e.getClass().getSimpleName());
        }
    }

    private long deadLetterCount(KafkaConsumer<String, String> consumer) {
        try {
            List<PartitionInfo> parts = consumer.partitionsFor(props.kafka().dlqTopic());
            if (parts == null || parts.isEmpty()) {
                return 0;
            }
            List<TopicPartition> tps = new ArrayList<>();
            for (PartitionInfo pi : parts) {
                tps.add(new TopicPartition(props.kafka().dlqTopic(), pi.partition()));
            }
            Map<TopicPartition, Long> end = consumer.endOffsets(tps);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(tps);
            long total = 0;
            for (TopicPartition tp : tps) {
                total += Math.max(0L, end.getOrDefault(tp, 0L) - begin.getOrDefault(tp, 0L));
            }
            return total;
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** 消费组状态：Stable（有活跃消费者）/ Empty（消费者已下线）/ Dead 等 */
    private String groupState() {
        Properties p = new Properties();
        p.put("bootstrap.servers", props.kafka().bootstrapServers());
        p.put("request.timeout.ms", "8000");
        p.put("default.api.timeout.ms", "10000");
        try (AdminClient admin = AdminClient.create(p)) {
            ConsumerGroupDescription desc = admin
                    .describeConsumerGroups(List.of(props.kafka().groupId()))
                    .all().get().get(props.kafka().groupId());
            return desc == null ? "UNKNOWN" : desc.state().toString();
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    private Properties consumerProps() {
        Properties p = new Properties();
        p.put("bootstrap.servers", props.kafka().bootstrapServers());
        p.put("group.id", props.kafka().groupId());
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("request.timeout.ms", "8000");
        p.put("default.api.timeout.ms", "10000");
        p.put("enable.auto.commit", "false");
        return p;
    }
}
