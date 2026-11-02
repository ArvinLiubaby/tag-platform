package com.tagplatform.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * 双数据源装配（模块边界内共享）：
 *   - dataSource（@Primary）：MySQL 8（生产，OB 兼容子集）/ H2（demo）——租户、标签元数据、幂等、Outbox、审计
 *   - starrocksDataSource：StarRocks（生产）/ H2（demo）——标签值宽表与变更流水
 * 说明：显式定义两个 DataSource 后，Boot 自动配置的数据源会因 @ConditionalOnMissingBean 而跳过；
 *       JdbcTemplate 同为显式定义，默认 jdbcTemplate 指向主库。
 */
@Configuration
public class DataSourceConfig {

    @Bean
    @Primary
    @ConfigurationProperties("spring.datasource.hikari")
    public DataSource dataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().build();
    }

    @Bean(name = "starrocksDataSource")
    public DataSource starrocksDataSource(AppProperties props) {
        AppProperties.StarRocks sr = props.starrocks();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(sr.url());
        config.setUsername(sr.username());
        config.setPassword(sr.password());
        config.setDriverClassName(sr.url().startsWith("jdbc:h2:") ? "org.h2.Driver" : "com.mysql.cj.jdbc.Driver");
        config.setPoolName("starrocks-pool");
        config.setMaximumPoolSize(8);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(5_000);
        return new HikariDataSource(config);
    }

    @Bean
    @Primary
    public JdbcTemplate jdbcTemplate(@Qualifier("dataSource") DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }

    @Bean(name = "starrocksJdbcTemplate")
    public JdbcTemplate starrocksJdbcTemplate(@Qualifier("starrocksDataSource") DataSource starrocksDataSource) {
        return new JdbcTemplate(starrocksDataSource);
    }
}
