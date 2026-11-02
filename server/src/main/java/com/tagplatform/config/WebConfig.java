package com.tagplatform.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.access.OpenApiAuthFilter;
import com.tagplatform.access.RateLimiter;
import com.tagplatform.access.SignatureVerifier;
import com.tagplatform.analytics.AnalyticsService;
import com.tagplatform.analytics.ApiCallAnalyticsFilter;
import com.tagplatform.common.RequestIdFilter;
import com.tagplatform.console.ConsoleApiAuthFilter;
import com.tagplatform.console.ConsoleSessionService;
import com.tagplatform.console.security.PermissionInterceptor;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web 装配：过滤器链与静态页入口。
 * 接口前缀统一为 /api/v1/{open|console}；兼容期同时保留旧前缀 /open/v1、/console/api。
 * 过滤器顺序：RequestIdFilter(1) → ApiCallAnalyticsFilter(5, /api/v1/open/*) →
 *             OpenApiAuthFilter(10, /api/v1/open/*) → ConsoleApiAuthFilter(20, /api/v1/console/*)
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final PermissionInterceptor permissionInterceptor;

    public WebConfig(PermissionInterceptor permissionInterceptor) {
        this.permissionInterceptor = permissionInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(permissionInterceptor)
                .addPathPatterns("/api/v1/console/**", "/console/api/**");
    }

    @Override
    public void addViewControllers(ViewControllerRegistry registry) {
        registry.addRedirectViewController("/", "/console/index.html");
        registry.addRedirectViewController("/console", "/console/index.html");
        registry.addRedirectViewController("/console/", "/console/index.html");
    }

    @Bean
    public FilterRegistrationBean<RequestIdFilter> requestIdFilter() {
        FilterRegistrationBean<RequestIdFilter> reg = new FilterRegistrationBean<>(new RequestIdFilter());
        reg.addUrlPatterns("/*");
        reg.setOrder(1);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ApiCallAnalyticsFilter> apiCallAnalyticsFilter(AnalyticsService analyticsService) {
        FilterRegistrationBean<ApiCallAnalyticsFilter> reg =
                new FilterRegistrationBean<>(new ApiCallAnalyticsFilter(analyticsService));
        reg.addUrlPatterns("/api/v1/open/*", "/open/v1/*");
        reg.setOrder(5);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<OpenApiAuthFilter> openApiAuthFilter(SignatureVerifier signatureVerifier,
                                                                       RateLimiter rateLimiter,
                                                                       ObjectMapper objectMapper) {
        FilterRegistrationBean<OpenApiAuthFilter> reg =
                new FilterRegistrationBean<>(new OpenApiAuthFilter(signatureVerifier, rateLimiter, objectMapper));
        reg.addUrlPatterns("/api/v1/open/*", "/open/v1/*");
        reg.setOrder(10);
        return reg;
    }

    @Bean
    public FilterRegistrationBean<ConsoleApiAuthFilter> consoleApiAuthFilter(ConsoleSessionService consoleSessionService,
                                                                             ObjectMapper objectMapper) {
        FilterRegistrationBean<ConsoleApiAuthFilter> reg =
                new FilterRegistrationBean<>(new ConsoleApiAuthFilter(consoleSessionService, objectMapper));
        reg.addUrlPatterns("/api/v1/console/*", "/console/api/*");
        reg.setOrder(20);
        return reg;
    }
}
