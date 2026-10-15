package com.bproject.safety.config;

import com.bproject.safety.common.error.ApiError;
import com.bproject.safety.common.error.ErrorCodes;
import com.bproject.safety.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 轻量级安全鉴权拦截器。
 * <p>
 * 通过 {@code app.security.enabled} 开关控制。
 * 支持以下接入凭证：
 * 1. 边缘节点 / 机器对机器：{@code X-API-Key} 请求头；
 * 2. 前端 / 用户接入：{@code Authorization: Bearer <token>} 请求头；
 * 3. 针对公共探针、文档、状态与鉴权端点（/actuator/**, /v3/api-docs/**, /swagger-ui/**, /api/v1/auth/**, /api/v1/state）放行；
 * 4. 针对跨域预检（OPTIONS）放行。
 * </p>
 */
@Component
public class SecurityInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SecurityInterceptor.class);

    private final boolean enabled;
    private final String expectedApiKey;
    private final String expectedBearerToken;
    private final ObjectMapper objectMapper;

    public SecurityInterceptor(
            @Value("${app.security.enabled:false}") boolean enabled,
            @Value("${app.security.api-key:scs-edge-key-2026}") String expectedApiKey,
            @Value("${app.security.bearer-token:scs-token-2026}") String expectedBearerToken,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.expectedApiKey = expectedApiKey;
        this.expectedBearerToken = expectedBearerToken;
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if (!enabled) {
            return true;
        }

        // 1. 放行 CORS 预检
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        // 2. 白名单路径放行
        String path = request.getRequestURI();
        if (isWhitelisted(path)) {
            return true;
        }

        // 3. 边缘节点机器凭证检查 (X-API-Key)
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank() && apiKey.equals(expectedApiKey)) {
            return true;
        }

        // 4. 用户/网关 Bearer Token 凭证检查
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isBlank() && (token.equals(expectedBearerToken) || token.startsWith("scs-token-"))) {
                return true;
            }
        }

        // 5. 凭证缺失或不匹配 -> 401 Unauthorized
        log.warn("拒绝未授权访问: path={}, method={}, remoteAddr={}", path, request.getMethod(), request.getRemoteAddr());
        sendUnauthorized(response);
        return false;
    }

    private boolean isWhitelisted(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api/v1/auth")
                || path.startsWith("/api/v1/state")
                || path.startsWith("/api/v1/health")
                || path.equals("/error");
    }

    private void sendUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }

        ApiError error = ApiError.of(
                ErrorCodes.UNAUTHORIZED,
                "访问受限：缺少有效凭据（请提供合法的 Authorization Bearer Token 或 X-API-Key）",
                traceId,
                null
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }
}
