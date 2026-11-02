package com.tagplatform.access.kv;

import com.tagplatform.config.AppProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * KV 存储装配：tag-platform.kv=redis（生产）| memory（demo）。
 */
@Configuration
public class KvConfig {

    @Bean
    public KeyValueStore keyValueStore(AppProperties props, ObjectProvider<StringRedisTemplate> redisProvider) {
        if ("memory".equalsIgnoreCase(props.kv())) {
            return new InMemoryKeyValueStore();
        }
        StringRedisTemplate template = redisProvider.getIfAvailable();
        if (template == null) {
            throw new IllegalStateException("tag-platform.kv=redis 但未配置 Redis；demo 模式请设置 tag-platform.kv=memory");
        }
        return new RedisKeyValueStore(template);
    }
}
