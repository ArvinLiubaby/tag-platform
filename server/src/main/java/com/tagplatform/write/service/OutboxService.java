package com.tagplatform.write.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Outbox 事件发布：与业务变更同事务落表（status=pending），
 * V2 版本接入投递器切换 Kafka（MVP 保留表结构不引入消息中间件）。
 */
@Service
public class OutboxService {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public OutboxService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void publish(String eventType, String tenantId, Object payload) {
        String eventId = UUID.randomUUID().toString().replace("-", "");
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalStateException("Outbox 事件序列化失败: " + eventType, e);
        }
        jdbcTemplate.update("""
                INSERT INTO outbox_event (event_id, event_type, tenant_id, payload, status, retry_count, created_at)
                VALUES (?, ?, ?, ?, 'pending', 0, ?)
                """, eventId, eventType, tenantId, json, LocalDateTime.now());
    }
}
