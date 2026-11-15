package com.tagplatform.write.service;

import com.tagplatform.write.web.dto.WriteResult;

/**
 * 写入结果 + 是否幂等重放。
 * <p>HTTP 通道只关心 {@link #result()}；Kafka 通道据此区分「首次写入」与「重复消息回放」，
 * 用于接入看板统计（重复不算异常，但要让运维看得见）。</p>
 */
public record WriteOutcome(WriteResult result, boolean replayed) {
}
