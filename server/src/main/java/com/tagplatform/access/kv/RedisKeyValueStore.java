package com.tagplatform.access.kv;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Redis KV 实现（生产模式）。
 */
public class RedisKeyValueStore implements KeyValueStore {

    private final StringRedisTemplate template;

    public RedisKeyValueStore(StringRedisTemplate template) {
        this.template = template;
    }

    @Override
    public Optional<String> get(String key) {
        return Optional.ofNullable(template.opsForValue().get(key));
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        template.opsForValue().set(key, value, ttl);
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        return Boolean.TRUE.equals(template.opsForValue().setIfAbsent(key, value, ttl));
    }

    @Override
    public void delete(String key) {
        template.delete(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        Long value = template.opsForValue().increment(key);
        if (value != null && value == 1L) {
            template.expire(key, ttl);
        }
        return value == null ? 1L : value;
    }
}
