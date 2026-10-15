package com.bproject.safety.config;

import com.bproject.safety.common.error.ApiError;
import com.bproject.safety.common.error.ErrorCodes;
import com.bproject.safety.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * 3. 针对公共探针、健康检查、文档、状态与鉴权端点放行；
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
            @Value("${app.security.enabled:true}") boolean enabled,
            @Value("${app.security.api-key:}") String expectedApiKey,
            @Value("${app.security.bearer-token:}") String expectedBearerToken,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.expectedApiKey = expectedApiKey != null ? expectedApiKey.trim() : "";
        this.expectedBearerToken = expectedBearerToken != null ? expectedBearerToken.trim() : "";
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (enabled) {
            if (expectedApiKey.isBlank()) {
                throw new IllegalStateException("Security is enabled (app.security.enabled=true) but app.security.api-key (APP_SECURITY_API_KEY) is not configured. Startup rejected.");
            }
            if (expectedBearerToken.isBlank()) {
                throw new IllegalStateException("Security is enabled (app.security.enabled=true) but app.security.bearer-token (APP_SECURITY_BEARER_TOKEN) is not configured. Startup rejected.");
            }
        }
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

        // 3. 边缘节点机器凭证检查 (X-API-Key，常量时间等值比较)
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank() && !expectedApiKey.isBlank()
                && MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), expectedApiKey.getBytes(StandardCharsets.UTF_8))) {
            return true;
        }

        // 4. 用户/网关 Bearer Token 凭证检查 (常量时间等值比较，禁止前缀绕过)
        String authHeader = request.getHeader("Authorization");
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            if (!token.isBlank() && !expectedBearerToken.isBlank()
                    && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedBearerToken.getBytes(StandardCharsets.UTF_8))) {
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
                || path.startsWith("/health")
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
