package com.tagplatform.write.inbound;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.PropertyNamingStrategies;
import com.tagplatform.common.BizException;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.access.kv.InMemoryKeyValueStore;
import com.tagplatform.config.AppProperties;
import com.tagplatform.write.service.WriteIngressService;
import com.tagplatform.write.service.WriteOutcome;
import com.tagplatform.write.web.dto.WriteResult;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.Acknowledgment;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Kafka 标签写入消费者：解析 / 租户解析 / 幂等重复 / 业务失败 / 可重试异常 / 上下文清理。
 * 不依赖真实 broker——直接构造 ConsumerRecord 调监听方法。
 */
class KafkaTagWriteListenerTest {

    private static final String TOPIC = "profile_tag_write";

    private ObjectMapper objectMapper;
    private WriteIngressService ingress;
    private KafkaDlqPublisher dlq;
    private Acknowledgment ack;
    private KafkaWriteMetrics metrics;
    private KafkaTagWriteListener listener;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper().setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE);
        ingress = mock(WriteIngressService.class);
        dlq = mock(KafkaDlqPublisher.class);
        ack = mock(Acknowledgment.class);
        AppProperties props = new AppProperties(null, "redis", "starrocks", null, null, null,
                new AppProperties.Kafka(true, "localhost:9092", TOPIC, "g", "profile_tag_write_dlq",
                        "risk_control", 1, 50));
        metrics = new KafkaWriteMetrics(new InMemoryKeyValueStore(), props);
        listener = new KafkaTagWriteListener(objectMapper, ingress, dlq, metrics, props);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    private WriteOutcome ok(String bizRequestId) {
        return new WriteOutcome(new WriteResult(bizRequestId, 1, 1, 0, List.of()), false);
    }

    private ConsumerRecord<String, String> record(String json, long offset) {
        return new ConsumerRecord<>(TOPIC, 0, offset, "DRV90001", json);
    }

    private String message(String tenantId, String bizRequestId) {
        String tenant = tenantId == null ? "" : "\"tenant_id\": \"" + tenantId + "\",";
        return "{" + tenant + "\"biz_request_id\": \"" + bizRequestId + "\","
                + "\"items\": [{\"object_type\": \"driver\", \"object_id\": \"DRV90001\","
                + " \"tag_code\": \"driver_relation\", \"tag_value\": \"公司员工\","
                + " \"operator\": {\"operator_id\": \"OP_DEMO\", \"operator_type\": \"human\"}}]}";
    }

    @Test
    void writesWithMessageTenantAndAcks() {
        WriteOutcome result = ok("biz-k-1");
        when(ingress.submit(any(), eq("risk_control"), anyString(), anyBoolean(), eq("kafka"))).thenReturn(result);

        listener.onMessage(record(message("risk_control", "biz-k-1"), 1L), ack);

        verify(ingress, times(1)).submit(any(), eq("risk_control"), anyString(), eq(false), eq("kafka"));
        verify(ack, times(1)).acknowledge();
        verify(dlq, never()).publish(any(), anyString());
    }

    @Test
    void headerTenantWinsOverBody() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(ok("biz-k-2"));
        ConsumerRecord<String, String> rec = record(message("from_body", "biz-k-2"), 2L);
        rec.headers().add("tenant_id", "from_header".getBytes(StandardCharsets.UTF_8));

        listener.onMessage(rec, ack);

        verify(ingress).submit(any(), eq("from_header"), anyString(), eq(false), eq("kafka"));
    }

    @Test
    void fallsBackToDefaultTenantWhenAbsent() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(ok("biz-k-3"));

        listener.onMessage(record(message(null, "biz-k-3"), 3L), ack);

        verify(ingress).submit(any(), eq("risk_control"), anyString(), eq(false), eq("kafka"));
    }

    @Test
    void missingBizRequestIdGoesToDlq() {
        listener.onMessage(record("{\"tenant_id\": \"risk_control\", \"items\": []}", 4L), ack);

        verify(dlq, times(1)).publish(any(), contains("biz_request_id"));
        verify(ack, times(1)).acknowledge();
        verify(ingress, never()).submit(any(), anyString(), anyString(), anyBoolean(), anyString());
    }

    @Test
    void invalidJsonGoesToDlq() {
        listener.onMessage(record("not-a-json", 5L), ack);

        verify(dlq, times(1)).publish(any(), contains("解析失败"));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void businessErrorGoesToDlqWithoutRetry() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "标签编码非法"));

        listener.onMessage(record(message("risk_control", "biz-k-4"), 6L), ack);

        verify(dlq, times(1)).publish(any(), contains("业务校验失败"));
        verify(ack, times(1)).acknowledge();
    }

    @Test
    void processingExceptionPropagatesForRetry() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new BizException(ErrorCode.REQUEST_PROCESSING));

        assertThatThrownBy(() -> listener.onMessage(record(message("risk_control", "biz-k-5"), 7L), ack))
                .isInstanceOf(BizException.class);

        verify(ack, never()).acknowledge();
        verify(dlq, never()).publish(any(), anyString());
    }

    @Test
    void clearsRequestContextAfterHandling() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(ok("biz-k-6"));

        listener.onMessage(record(message("risk_control", "biz-k-6"), 8L), ack);

        assertThat(RequestContext.getTenantId()).isNull();
        assertThat(RequestContext.getAppKey()).isNull();
    }

    @Test
    void recordsMetricsForNewAndReplayedMessages() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenReturn(ok("biz-m-1"))
                .thenReturn(new WriteOutcome(new WriteResult("biz-m-2", 1, 1, 0, List.of()), true));

        listener.onMessage(record(message("risk_control", "biz-m-1"), 11L), ack);
        listener.onMessage(record(message("risk_control", "biz-m-2"), 12L), ack);

        assertThat(metrics.received()).isEqualTo(2);
        assertThat(metrics.succeeded()).isEqualTo(1);
        assertThat(metrics.duplicates()).isEqualTo(1);
        assertThat(metrics.failed()).isZero();
        assertThat(metrics.lastConsumedAt()).isNotNull();
    }

    @Test
    void recordsFailureMetricsWhenBusinessRejected() {
        when(ingress.submit(any(), anyString(), anyString(), anyBoolean(), anyString()))
                .thenThrow(new BizException(ErrorCode.PARAM_INVALID, "标签编码非法"));

        listener.onMessage(record(message("risk_control", "biz-m-3"), 13L), ack);

        assertThat(metrics.received()).isEqualTo(1);
        assertThat(metrics.succeeded()).isZero();
        assertThat(metrics.failed()).isEqualTo(1);
        assertThat(metrics.lastError()).contains("业务校验失败");
    }
}
