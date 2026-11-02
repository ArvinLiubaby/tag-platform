package com.tagplatform.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tagplatform.common.ApiResponse;
import com.tagplatform.common.ErrorCode;
import com.tagplatform.common.RequestContext;
import com.tagplatform.console.model.ConsoleSession;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * 控制台鉴权过滤器（/api/v1/console/*，兼容旧前缀 /console/api/*）：
 *   Token 提取顺序：X-Console-Token 头 → Authorization: Bearer；
 *   登录接口 /api/v1/console/auth/login（旧前缀 /console/api/auth/login）放行（shouldNotFilter）。
 */
public class ConsoleApiAuthFilter extends OncePerRequestFilter {

    public static final String HEADER = "X-Console-Token";

    private static final String LOGIN_PATH = "/api/v1/console/auth/login";
    private static final String LEGACY_LOGIN_PATH = "/console/api/auth/login";
    private static final String VERSION_PATH = "/api/v1/console/system/version";
    private static final String LEGACY_VERSION_PATH = "/console/api/system/version";

    private final ConsoleSessionService sessions;
    private final ObjectMapper objectMapper;

    public ConsoleApiAuthFilter(ConsoleSessionService sessions, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return LOGIN_PATH.equals(uri) || LEGACY_LOGIN_PATH.equals(uri)
                || VERSION_PATH.equals(uri) || LEGACY_VERSION_PATH.equals(uri);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Optional<ConsoleSession> session = sessions.findByToken(extractToken(request));
        if (session.isEmpty()) {
            response.setStatus(ErrorCode.CONSOLE_UNAUTHORIZED.httpStatus());
            response.setContentType("application/json;charset=UTF-8");
            ApiResponse<Object> body = ApiResponse.of(ErrorCode.CONSOLE_UNAUTHORIZED.code(),
                    ErrorCode.CONSOLE_UNAUTHORIZED.message(), null);
            response.getWriter().write(objectMapper.writeValueAsString(body));
            return;
        }
        ConsoleSession s = session.get();
        String tenantId = s.tenantId();
        // 管理员可通过 X-Console-Tenant 显式切换工作台租户；其他角色始终使用账号绑定租户
        if ("ADMIN".equals(s.role())) {
            String headerTenant = request.getHeader("X-Console-Tenant");
            if (headerTenant != null && !headerTenant.isBlank()) {
                tenantId = headerTenant.trim();
            }
        }
        RequestContext.setConsoleUser(s.username(), s.role(), s.displayName(), tenantId,
                s.operatorId(), s.operatorName(), s.operatorOrg(), s.team());
        chain.doFilter(request, response);
    }

    public static String extractToken(HttpServletRequest request) {
        String token = request.getHeader(HEADER);
        if (token == null || token.isBlank()) {
            String authorization = request.getHeader("Authorization");
            if (authorization != null && authorization.startsWith("Bearer ")) {
                token = authorization.substring(7).trim();
            }
        }
        return token == null || token.isBlank() ? null : token;
    }
}
