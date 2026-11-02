package com.tagplatform.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * 生产启动时自动执行 StarRocks 标签值表 DDL（CREATE TABLE IF NOT EXISTS，幂等）。
 * demo 模式（V1001 迁移已在 H2 建表）不执行；失败快速中断启动，提示检查 StarRocks 连通性。
 */
@Component
@Profile("!demo")
@ConditionalOnProperty(prefix = "tag-platform.starrocks", name = "init-schema", havingValue = "true", matchIfMissing = true)
public class StarRocksSchemaInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StarRocksSchemaInitializer.class);

    private final JdbcTemplate starrocksJdbcTemplate;

    public StarRocksSchemaInitializer(@Qualifier("starrocksJdbcTemplate") JdbcTemplate starrocksJdbcTemplate) {
        this.starrocksJdbcTemplate = starrocksJdbcTemplate;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        String ddl = new ClassPathResource("db/starrocks/schema.sql")
                .getContentAsString(StandardCharsets.UTF_8);
        int executed = 0;
        for (String raw : ddl.split(";")) {
            String stmt = stripComments(raw);
            if (stmt.isBlank()) {
                continue;
            }
            try {
                starrocksJdbcTemplate.execute(stmt);
                executed++;
            } catch (Exception e) {
                throw new IllegalStateException("StarRocks 建表失败，请检查 tag-platform.starrocks.* 配置与连接: "
                        + firstLine(stmt), e);
            }
        }
        log.info("StarRocks 标签值表校验完成，执行语句数={}", executed);
    }

    /** 去掉 -- 行注释与首尾空白 */
    private String stripComments(String sql) {
        StringBuilder sb = new StringBuilder();
        for (String line : sql.split("\n")) {
            int idx = line.indexOf("--");
            sb.append(idx >= 0 ? line.substring(0, idx) : line).append('\n');
        }
        return sb.toString().trim();
    }

    private String firstLine(String stmt) {
        int idx = stmt.indexOf('\n');
        return idx > 0 ? stmt.substring(0, idx) : stmt;
    }
}
