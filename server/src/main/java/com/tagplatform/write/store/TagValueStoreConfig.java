package com.tagplatform.write.store;

import com.tagplatform.config.AppProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 标签值存储装配：tag-platform.store=starrocks（生产）| h2（demo）。
 * 两者共用 @Qualifier("starrocksJdbcTemplate")（demo 下已指向 H2 文件库）。
 */
@Configuration
public class TagValueStoreConfig {

    @Bean
    public TagValueStore tagValueStore(AppProperties props,
                                       @Qualifier("starrocksJdbcTemplate") JdbcTemplate starrocksJdbcTemplate) {
        return switch (props.store().toLowerCase()) {
            case "h2" -> new H2TagValueStore(starrocksJdbcTemplate);
            case "starrocks" -> new StarRocksTagValueStore(starrocksJdbcTemplate);
            default -> throw new IllegalStateException("未知 tag-platform.store: " + props.store());
        };
    }
}
