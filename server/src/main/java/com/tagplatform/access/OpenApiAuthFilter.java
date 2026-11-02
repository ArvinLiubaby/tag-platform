package com.tagplatform.access;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.BizException;
import com.tagplatform.common.RequestContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * 开放 API 鉴权过滤器（/api/v1/open/*，兼容旧前缀 /open/v1/*）：
 *  1. IP 频控（验签前拦截，防未鉴权爆破）
 *  2. 缓存请求体原始字节 → HMAC 验签 + 时间窗 + Nonce 防重放
 *  3. 以可重复读的请求包装继续过滤链
 * 失败时直接输出统一错误响应（不进入 Controller）。
 */
public class OpenApiAuthFilter extends OncePerRequestFilter {

    private final SignatureVerifier signatureVerifier;
    private final RateLimiter rateLimiter;
    private final ObjectMapper objectMapper;

    public OpenApiAuthFilter(SignatureVerifier signatureVerifier, RateLimiter rateLimiter, ObjectMapper objectMapper) {
        this.signatureVerifier = signatureVerifier;
        this.rateLimiter = rateLimiter;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        byte[] body;
        try {
            rateLimiter.checkIp(RequestContext.getSourceIp());
            body = request.getInputStream().readAllBytes();
            signatureVerifier.verify(request, body);
        } catch (BizException e) {
            writeError(response, e);
            return;
        }
        chain.doFilter(new BodyCachingRequestWrapper(request, body), response);
    }

    private void writeError(HttpServletResponse response, BizException e) throws IOException {
        RequestContext.setBizCode(e.getErrorCode().code());
        response.setStatus(e.getErrorCode().httpStatus());
        response.setContentType("application/json;charset=UTF-8");
        ApiResponse<Object> body = new ApiResponse<>(e.getErrorCode().code(), e.getMessage(),
                RequestContext.getRequestId(), e.getData());
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
