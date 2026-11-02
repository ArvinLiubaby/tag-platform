package com.tagplatform.console.web;

import com.tagplatform.common.ApiResponse;
import com.tagplatform.console.security.PublicConsoleEndpoint;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.info.BuildProperties;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;

/**
 * 控制台公共系统信息：版本号来自构建生成的 build-info，前端不硬编码。
 */
@RestController
@PublicConsoleEndpoint
@RequestMapping({"/api/v1/console/system", "/console/api/system"})
public class ConsoleSystemController {

    private final ObjectProvider<BuildProperties> buildProperties;

    public ConsoleSystemController(ObjectProvider<BuildProperties> buildProperties) {
        this.buildProperties = buildProperties;
    }

    @GetMapping("/version")
    public ApiResponse<VersionView> version() {
        BuildProperties build = buildProperties.getIfAvailable();
        String version = build == null || build.getVersion() == null ? "dev" : build.getVersion();
        Instant buildTime = build == null ? null : build.getTime();
        return ApiResponse.ok(new VersionView(version, buildTime));
    }

    public record VersionView(String version, Instant buildTime) {
    }
}
