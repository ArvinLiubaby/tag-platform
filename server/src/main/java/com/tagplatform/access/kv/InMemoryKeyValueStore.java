package com.tagplatform.access.kv;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存 KV 实现（demo 模式）：惰性过期，单进程内有效。
 */
public class InMemoryKeyValueStore implements KeyValueStore {

    private record Entry(String value, long expireAtMillis) {
        boolean expired() {
            return System.currentTimeMillis() > expireAtMillis;
        }
    }

    private final ConcurrentHashMap<String, Entry> map = new ConcurrentHashMap<>();

    @Override
    public Optional<String> get(String key) {
        Entry entry = map.get(key);
        if (entry == null || entry.expired()) {
            if (entry != null) {
                map.remove(key);
            }
            return Optional.empty();
        }
        return Optional.of(entry.value());
    }

    @Override
    public void set(String key, String value, Duration ttl) {
        map.put(key, new Entry(value, System.currentTimeMillis() + ttl.toMillis()));
    }

    @Override
    public boolean setIfAbsent(String key, String value, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        // 用持有标记判断本次是否真正写入：不能以 value/expireAt 相等来推断（同一毫秒的重放请求会误判为首次）
        boolean[] created = new boolean[1];
        map.compute(key, (k, old) -> {
            if (old == null || old.expired()) {
                created[0] = true;
                return new Entry(value, expireAt);
            }
            return old;
        });
        return created[0];
    }

    @Override
    public void delete(String key) {
        map.remove(key);
    }

    @Override
    public long increment(String key, Duration ttl) {
        long expireAt = System.currentTimeMillis() + ttl.toMillis();
        Entry entry = map.compute(key, (k, old) ->
                (old == null || old.expired()) ? new Entry("1", expireAt) : new Entry(String.valueOf(
                        Long.parseLong(old.value()) + 1), old.expireAtMillis()));
        return Long.parseLong(entry.value());
    }
}
