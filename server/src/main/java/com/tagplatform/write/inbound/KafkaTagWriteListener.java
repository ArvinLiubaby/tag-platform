package com.tagplatform.write.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.config.AppProperties;
import com.tagplatform.write.service.WriteIngressService;
import com.tagplatform.write.service.WriteOutcome;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * Kafka 标签写入消费者：消息体与开放 API 的 WriteRequest 同构，走同一个 {@link WriteIngressService}，
 * 因此校验、幂等、Outbox、任务联动、审计与 HTTP 通道完全一致。
 *
 * <p>可靠性约定：</p>
 * <ul>
 *   <li>手动 ack：业务处理完（含"已判定失败并转死信"）才提交位点；</li>
 *   <li>幂等重复：{@code biz_request_id} 命中已完成请求时回放首次响应，视为成功 ack（不重复写入）；</li>
 *   <li>首次请求仍在处理（3004）：抛出让容器退避重试，不丢消息；</li>
 *   <li>业务校验失败、消息非法：直接转死信并 ack，重试无意义；</li>
 *   <li>基础设施异常：抛出让 {@code DefaultErrorHandler} 重试，耗尽后自动转死信。</li>
 * </ul>
 *
 * <p>频控：Kafka 是内部可信通道，跳过「每操作员 20 QPS / 同对象同标签 1 QPS」限制，
 * 仅保留单批 batch-max 上限。</p>
 */
@Component
@ConditionalOnProperty(prefix = "tag-platform.kafka", name = "enabled", havingValue = "true")
public class KafkaTagWriteListener {

    private static final Logger log = LoggerFactory.getLogger(KafkaTagWriteListener.class);

    private final ObjectMapper objectMapper;
    private final WriteIngressService writeIngressService;
    private final KafkaDlqPublisher dlqPublisher;
    private final KafkaWriteMetrics metrics;
    private final AppProperties props;

    public KafkaTagWriteListener(ObjectMapper objectMapper, WriteIngressService writeIngressService,
                                 KafkaDlqPublisher dlqPublisher, KafkaWriteMetrics metrics,
                                 AppProperties props) {
        this.objectMapper = objectMapper;
        this.writeIngressService = writeIngressService;
        this.dlqPublisher = dlqPublisher;
        this.metrics = metrics;
        this.props = props;
    }

    @KafkaListener(topics = "${tag-platform.kafka.topic:profile_tag_write}",
            groupId = "${tag-platform.kafka.group-id:tag-platform-write}",
            concurrency = "${tag-platform.kafka.concurrency:1}",
            containerFactory = "tagWriteKafkaListenerContainerFactory")
    public void onMessage(ConsumerRecord<String, String> record, Acknowledgment ack) {
        String trace = record.topic() + "-" + record.partition() + "@" + record.offset();
        String requestId = "kafka-" + trace;
        metrics.onReceived();

        KafkaWriteMessage message;
        try {
            message = objectMapper.readValue(record.value(), KafkaWriteMessage.class);
        } catch (Exception e) {
            String reason = "消息解析失败（需与 WriteRequest 同构的 JSON）: " + e.getMessage();
            dlqPublisher.publish(record, reason);
            metrics.onFailed(reason);
            ack.acknowledge();
            return;
        }
        if (message == null || message.bizRequestId() == null || message.bizRequestId().isBlank()) {
            String reason = "缺少 biz_request_id（幂等键必填，重投须保持不变）";
            dlqPublisher.publish(record, reason);
            metrics.onFailed(reason);
            ack.acknowledge();
            return;
        }
        if (message.items() == null || message.items().isEmpty()) {
            String reason = "items 为空，无可写入条目";
            dlqPublisher.publish(record, reason);
            metrics.onFailed(reason);
            ack.acknowledge();
            return;
        }
        String tenantId = resolveTenant(record, message);
        if (tenantId == null) {
            String reason = "缺少 tenant_id（消息体或 header）且未配置 tag-platform.kafka.default-tenant";
            dlqPublisher.publish(record, reason);
            metrics.onFailed(reason);
            ack.acknowledge();
            return;
        }

        try {
            RequestContext.clear();
            RequestContext.setTenantId(tenantId);
            RequestContext.setAppKey(message.appKey() == null || message.appKey().isBlank()
                    ? "kafka:" + record.topic() : message.appKey().trim());
            RequestContext.setRequestId(requestId);

            WriteOutcome outcome = writeIngressService.submit(message.toRequest(), tenantId, requestId, false, "kafka");
            metrics.onSuccess(outcome.replayed());
            ack.acknowledge();
            log.info("Kafka 标签写入完成 {} tenant={} bizRequestId={} items={} accepted={} pendingAudit={} replayed={}",
                    trace, tenantId, message.bizRequestId(), outcome.result().total(),
                    outcome.result().accepted(), outcome.result().pendingAudit(), outcome.replayed());
        } catch (BizException e) {
            if (e.getErrorCode() == ErrorCode.REQUEST_PROCESSING) {
                log.warn("Kafka 写入首次请求仍在处理，退避重试 {} bizRequestId={}", trace, message.bizRequestId());
                metrics.onRetry("首次请求仍在处理（3004），退避重试");
                throw e;
            }
            String reason = "业务校验失败[" + e.getErrorCode() + "]: " + e.getMessage();
            dlqPublisher.publish(record, reason);
            metrics.onFailed(reason);
            ack.acknowledge();
            log.warn("Kafka 标签写入被拒 {} bizRequestId={} code={} msg={}",
                    trace, message.bizRequestId(), e.getErrorCode(), e.getMessage());
        } catch (RuntimeException e) {
            log.warn("Kafka 标签写入异常，触发重试 {} bizRequestId={}: {}",
                    trace, message.bizRequestId(), e.toString());
            metrics.onRetry("写入异常，触发重试: " + e.getClass().getSimpleName());
            throw e;
        } finally {
            RequestContext.clear();
        }
    }

    /** 租户解析：消息 header tenant_id → 消息体 tenant_id → 配置默认租户 */
    private String resolveTenant(ConsumerRecord<String, String> record, KafkaWriteMessage message) {
        Header header = record.headers() == null ? null : record.headers().lastHeader("tenant_id");
        if (header != null && header.value() != null) {
            String fromHeader = new String(header.value(), StandardCharsets.UTF_8).trim();
            if (!fromHeader.isEmpty()) {
                return fromHeader;
            }
        }
        if (message.tenantId() != null && !message.tenantId().isBlank()) {
            return message.tenantId().trim();
        }
        String fallback = props.kafka().defaultTenant();
        return fallback == null || fallback.isBlank() ? null : fallback.trim();
    }
}
