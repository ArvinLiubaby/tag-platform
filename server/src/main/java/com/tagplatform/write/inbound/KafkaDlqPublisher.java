package com.tagplatform.write.inbound;

import com.tagplatform.config.AppProperties;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;

/**
 * 死信投递：解析失败、缺幂等键、业务校验失败（重试无意义）的消息转投 DLQ，
 * 保留原消息与来源位置，便于排查与补偿。
 */
@Component
@ConditionalOnProperty(prefix = "tag-platform.kafka", name = "enabled", havingValue = "true")
public class KafkaDlqPublisher {

    private static final Logger log = LoggerFactory.getLogger(KafkaDlqPublisher.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final String dlqTopic;

    public KafkaDlqPublisher(@Qualifier("tagWriteKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                             AppProperties props) {
        this.kafkaTemplate = kafkaTemplate;
        this.dlqTopic = props.kafka().dlqTopic();
    }

    public void publish(ConsumerRecord<String, String> record, String reason) {
        ProducerRecord<String, String> dlq = new ProducerRecord<>(dlqTopic, record.key(), record.value());
        header(dlq, "x-error", reason);
        header(dlq, "x-origin-topic", record.topic());
        header(dlq, "x-origin-partition", String.valueOf(record.partition()));
        header(dlq, "x-origin-offset", String.valueOf(record.offset()));
        header(dlq, "x-failed-at", OffsetDateTime.now().toString());
        kafkaTemplate.send(dlq);
        log.error("Kafka 消息进入死信 dlq={} 原位置={}-{}@{} 原因={}",
                dlqTopic, record.topic(), record.partition(), record.offset(), reason);
    }

    private static void header(ProducerRecord<String, String> record, String key, String value) {
        record.headers().add(key, (value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }
}
