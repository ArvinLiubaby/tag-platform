package com.tagplatform.write.inbound;

import com.tagplatform.write.web.dto.WriteRequest;

import java.util.List;

/**
 * Kafka 标签写入消息：与开放 API 的 {@link WriteRequest} 同构，额外允许两个可选字段——
 * <ul>
 *   <li>{@code tenant_id}：租户归属（Kafka 通道没有 AK 签名上下文，由消息声明；缺省回落配置默认租户）</li>
 *   <li>{@code app_key}：调用方标识，用于审计与任务项归属（缺省记为 kafka:topic）</li>
 * </ul>
 * 其余字段（biz_request_id / items / task_id）与 HTTP 接口完全一致。
 */
public record KafkaWriteMessage(
        String tenantId,
        String appKey,
        String bizRequestId,
        List<WriteRequest.WriteItem> items,
        Long taskId) {

    public WriteRequest toRequest() {
        return new WriteRequest(bizRequestId, items, taskId);
    }
}
