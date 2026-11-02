package com.tagplatform.access.kv;

import java.time.Duration;
import java.util.Optional;

/**
 * KV 存储抽象：生产用 Redis，demo 用内存实现。
 * 用途：Nonce 防重放、频控计数、控制台会话 Token、幂等短缓存。
 */
public interface KeyValueStore {

    Optional<String> get(String key);

    void set(String key, String value, Duration ttl);

    /** 原子 set-if-absent；返回 true 表示写入成功（键此前不存在） */
    boolean setIfAbsent(String key, String value, Duration ttl);

    void delete(String key);

    /** 原子自增；键不存在或已过期时从 1 开始并设置 TTL，返回自增后的值 */
    long increment(String key, Duration ttl);
}
