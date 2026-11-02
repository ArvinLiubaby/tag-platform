package com.tagplatform.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * tag-platform.* 配置绑定（application.yml / application-demo.yml）。
 */
@ConfigurationProperties(prefix = "tag-platform")
public record AppProperties(
        @DefaultValue Crypto crypto,
        @DefaultValue("redis") String kv,
        @DefaultValue("starrocks") String store,
        @DefaultValue StarRocks starrocks,
        @DefaultValue Limits limits,
        @DefaultValue Console console) {

    /** 可逆加密主密钥（生产必须通过环境变量 TAG_PLATFORM_MASTER_KEY 覆盖） */
    public record Crypto(@DefaultValue("tag-platform-demo-master-key-0123456789") String masterKey) {
    }

    /** 标签值存储（StarRocks；demo 指向 H2） */
    public record StarRocks(@DefaultValue("jdbc:mysql://localhost:9030/tag_platform") String url,
                            @DefaultValue("root") String username,
                            @DefaultValue("") String password,
                            @DefaultValue("true") boolean initSchema) {
    }

    /** 频控与批量约束 */
    public record Limits(@DefaultValue("100") int ipQps,
                         @DefaultValue("20") int operatorQps,
                         @DefaultValue("1") int objectTagQps,
                         @DefaultValue("1000") int batchMax,
                         @DefaultValue("300") long timestampSkewSeconds,
                         @DefaultValue("60") long nonceTtlSeconds,
                         @DefaultValue("24") long idempotentTtlHours) {
    }

    /** 控制台会话 */
    public record Console(@DefaultValue("8") long tokenTtlHours) {
    }

    /** demo 形态判断（内存 KV / H2 存储） */
    public boolean demo() {
        return "memory".equals(kv);
    }
}
