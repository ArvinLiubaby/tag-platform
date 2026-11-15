package com.tagplatform.config;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.annotation.EnableKafka;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.ContainerProperties;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.HashMap;
import java.util.Map;

/**
 * Kafka 标签写入通道装配（仅 tag-platform.kafka.enabled=true 时生效）。
 *
 * <p>刻意不依赖 spring.kafka.* 自动配置：配置项集中在 tag-platform.kafka 下，
 * 关闭时不装配任何 Kafka Bean，无 broker 的本地/demo 环境不受影响。</p>
 *
 * <p>值反序列化用 String 而非 JsonDeserializer，由应用自身的 ObjectMapper
 * （全局 SNAKE_CASE）解析，保证与 HTTP 接口同一套字段命名规则，解析失败可精确进死信。</p>
 */
@Configuration
@EnableKafka
@ConditionalOnProperty(prefix = "tag-platform.kafka", name = "enabled", havingValue = "true")
public class KafkaWriteConfig {

    @Bean
    public ConsumerFactory<String, String> tagWriteConsumerFactory(AppProperties props) {
        AppProperties.Kafka kafka = props.kafka();
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.bootstrapServers());
        cfg.put(ConsumerConfig.GROUP_ID_CONFIG, kafka.groupId());
        cfg.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        cfg.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        // 首次加入消费组从最新位点开始：不补历史消息（避免接入瞬间批量重放），
        // 之后按已提交位点续消费，平台重启不会漏消息。确需补历史时由运维手动重置位点。
        cfg.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "latest");
        cfg.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        cfg.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, kafka.maxPollRecords());
        return new DefaultKafkaConsumerFactory<>(cfg);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, String> tagWriteKafkaListenerContainerFactory(
            ConsumerFactory<String, String> tagWriteConsumerFactory,
            DefaultErrorHandler tagWriteErrorHandler) {
        ConcurrentKafkaListenerContainerFactory<String, String> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(tagWriteConsumerFactory);
        factory.getContainerProperties().setAckMode(ContainerProperties.AckMode.MANUAL_IMMEDIATE);
        // topic 尚未创建时不阻塞启动（生产由运维预先建 topic + 死信 topic）
        factory.getContainerProperties().setMissingTopicsFatal(false);
        factory.setCommonErrorHandler(tagWriteErrorHandler);
        return factory;
    }

    @Bean
    public ProducerFactory<String, String> tagWriteProducerFactory(AppProperties props) {
        Map<String, Object> cfg = new HashMap<>();
        cfg.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, props.kafka().bootstrapServers());
        cfg.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        cfg.put(ProducerConfig.ACKS_CONFIG, "1");
        return new DefaultKafkaProducerFactory<>(cfg);
    }

    @Bean
    public KafkaTemplate<String, String> tagWriteKafkaTemplate(
            ProducerFactory<String, String> tagWriteProducerFactory) {
        return new KafkaTemplate<>(tagWriteProducerFactory);
    }

    /**
     * 基础设施异常（DB / 存储抖动）重试 3 次（间隔 2s），耗尽后转死信；
     * 业务校验失败由监听器内部直接转死信，不走重试。
     */
    @Bean
    public DefaultErrorHandler tagWriteErrorHandler(KafkaTemplate<String, String> tagWriteKafkaTemplate,
                                                    AppProperties props) {
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(tagWriteKafkaTemplate,
                (record, ex) -> new TopicPartition(props.kafka().dlqTopic(), -1));
        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(2000L, 3L));
        handler.setLogLevel(KafkaException.Level.WARN);
        return handler;
    }
}
