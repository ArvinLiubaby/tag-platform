package com.tagplatform;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 标签画像采集平台 — 模块化单体应用。
 * 模块划分（包边界即未来微服务拆分边界）：
 *   access 接入鉴权 / meta 元数据 / write 写入 / query 查询画像 / audit 审核治理 / console 控制台 BFF
 */
@SpringBootApplication
@ConfigurationPropertiesScan
public class TagPlatformApplication {

    public static void main(String[] args) {
        SpringApplication.run(TagPlatformApplication.class, args);
    }
}
