package com.tagplatform.analytics;

import com.tagplatform.access.SignatureVerifier;
import com.tagplatform.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerMapping;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * 开放 API 埋点过滤器（order 5，位于 RequestIdFilter 之后、OpenApiAuthFilter 之前）：
 *   - 无论鉴权成功还是失败都会记录一行 api_call_log；
 *   - 标签级 query/write/delete 次数由 RequestContext 采集器在业务服务中累加；
 *   - 埋点写入失败只记日志，不影响接口响应。
 */
public class ApiCallAnalyticsFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiCallAnalyticsFilter.class);

    private final AnalyticsService analyticsService;

    public ApiCallAnalyticsFilter(AnalyticsService analyticsService) {
        this.analyticsService = analyticsService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            chain.doFilter(request, response);
            return;
        }

        long started = System.currentTimeMillis();
        RequestContext.startAnalytics();
        boolean failed = false;
        try {
            chain.doFilter(request, response);
        } catch (ServletException | IOException | RuntimeException e) {
            failed = true;
            throw e;
        } finally {
            try {
                RequestContext.RequestAnalytics analytics = RequestContext.getAnalytics();
                Map<String, Integer> queries = analytics == null ? Map.of() : analytics.queries();
                Map<String, Integer> writes = analytics == null ? Map.of() : analytics.writes();
                Map<String, Integer> deletes = analytics == null ? Map.of() : analytics.deletes();

                Integer bizCode = RequestContext.getBizCode();
                int httpStatus = response.getStatus();
                boolean success = !failed && httpStatus >= 200 && httpStatus < 400
                        && (bizCode == null || bizCode == 0);

                String appKey = RequestContext.getAppKey();
                if (appKey == null || appKey.isBlank()) {
                    appKey = request.getHeader(SignatureVerifier.HEADER_APP_KEY);
                }

                ApiCallRecord record = new ApiCallRecord(
                        RequestContext.getTenantId(),
                        truncate(appKey, 64),
                        request.getMethod(),
                        resolveEndpoint(request),
                        httpStatus,
                        bizCode,
                        success,
                        (int) Math.min(Integer.MAX_VALUE, System.currentTimeMillis() - started),
                        analytics == null ? null : truncate(analytics.operatorId(), 64),
                        analytics == null ? null : truncate(analytics.objectType(), 32),
                        analytics == null ? null : truncate(analytics.objectId(), 128),
                        truncate(RequestContext.getRequestId(), 64),
                        truncate(RequestContext.getSourceIp(), 64),
                        LocalDateTime.now(),
                        queries, writes, deletes);
                analyticsService.recordCall(record);
            } catch (Exception e) {
                log.warn("开放 API 埋点写入失败 request_id={} msg={}",
                        RequestContext.getRequestId(), e.getMessage());
            } finally {
                RequestContext.clearAnalytics();
            }
        }
    }

    /** 归一化端点：优先 Spring 路由模板，旧前缀统一为新前缀，避免路径参数造成高基数。 */
    static String resolveEndpoint(HttpServletRequest request) {
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String endpoint = pattern == null ? request.getRequestURI() : pattern.toString();
        if (endpoint == null || endpoint.isBlank()) {
            endpoint = request.getRequestURI();
        }
        if (endpoint.startsWith("/open/v1")) {
            endpoint = "/api/v1/open" + endpoint.substring("/open/v1".length());
        }
        return truncate(endpoint, 160);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }
}
