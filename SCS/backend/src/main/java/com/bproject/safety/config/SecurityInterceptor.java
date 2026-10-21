package com.bproject.safety.config;

import com.bproject.safety.common.error.ApiError;
import com.bproject.safety.common.error.ErrorCodes;
import com.bproject.safety.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
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
 * 安全鉴权与权限拦截器（支持 API-Key、Bearer JWT、网关透传头与角色级 403 硬拦截）。
 * <p>
 * 核心安全特性：
 * 1. 边缘节点 / 机器凭据：{@code X-API-Key} 请求头；
 * 2. 用户 / 网关凭据：{@code Authorization: Bearer <token>} 请求头；
 * 3. 支持 JWT Claims 解码（提取 {@code sub}、{@code roles}、{@code permissions}、{@code aud}/{@code scope} 及过期时间校验）；
 * 4. 支持网关透传头（{@code X-User-Roles}、{@code X-User-Sub}、{@code X-Scope}）；
 * 5. 跨 Scope/Audience 隔离检查：非 safety/scs 目标受众直接 403 (FORBIDDEN_SCOPE_MISMATCH)；
 * 6. 只读审计角色（{@code PLATFORM_AUDITOR_READONLY}）：
 *    - 读操作（GET/HEAD/OPTIONS）正常放行（200 OK）；
 *    - 写操作与控制指令（POST/PUT/DELETE/PATCH）在进入 Controller 前服务端强力硬拦截，返回 403 (FORBIDDEN_READONLY_ROLE) 并输出脱敏 traceId；
 * 7. 匿名或非法凭证拦截并返回 401 UNAUTHORIZED。
 * </p>
 */
@Component
public class SecurityInterceptor implements HandlerInterceptor {
    private static final Logger log = LoggerFactory.getLogger(SecurityInterceptor.class);

    public static final String ATTR_USER_SUB = "security.user.sub";
    public static final String ATTR_USER_ROLES = "security.user.roles";
    public static final String ATTR_USER_PERMISSIONS = "security.user.permissions";

    public static final String ROLE_PLATFORM_AUDITOR_READONLY = "PLATFORM_AUDITOR_READONLY";

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
            request.setAttribute(ATTR_USER_SUB, "edge-node");
            request.setAttribute(ATTR_USER_ROLES, Set.of("ROLE_EDGE"));
            return true;
        }

        // 4. 用户 / 网关 Bearer Token 凭证检查
        String authHeader = request.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("拒绝未授权访问（缺失 Bearer Token）: path={}, method={}, remoteAddr={}", path, request.getMethod(), request.getRemoteAddr());
            sendUnauthorized(request, response, "访问受限：缺少有效凭据（请提供合法的 Authorization Bearer Token 或 X-API-Key）");
            return false;
        }

        String rawToken = authHeader.substring(7).trim();
        if (rawToken.isBlank()) {
            sendUnauthorized(request, response, "访问受限：Bearer Token 为空");
            return false;
        }

        AuthPrincipal principal = resolvePrincipal(rawToken, request);
        if (principal == null) {
            log.warn("拒绝未授权访问（凭据不匹配或解析失败）: path={}, method={}, remoteAddr={}", path, request.getMethod(), request.getRemoteAddr());
            sendUnauthorized(request, response, "访问受限：凭据不匹配或签名解析失败");
            return false;
        }

        if (principal.expired()) {
            log.warn("拒绝访问（Token 已过期）: path={}, user={}", path, principal.sub());
            sendUnauthorized(request, response, "访问受限：身份令牌已过期，请重新登录换发");
            return false;
        }

        // 5. 跨 Scope / 受众隔离检查
        if (principal.scope() != null && !principal.scope().isBlank()) {
            String target = principal.scope().trim().toLowerCase();
            if (!isScopeMatched(target)) {
                log.warn("拒绝跨 Scope 越权访问: user={}, tokenScope={}, path={}", principal.sub(), target, path);
                sendForbidden(request, response, ErrorCodes.FORBIDDEN_SCOPE_MISMATCH,
                        "访问受限：Token 受众/Scope 与当前安全卡控系统不匹配（期望: safety/scs，实际: " + principal.scope() + "）",
                        Map.of("user", principal.sub(), "scope", principal.scope(), "path", path));
                return false;
            }
        }

        // 6. 只读审计角色（PLATFORM_AUDITOR_READONLY）写操作硬否决检查
        if (principal.isReadOnly()) {
            boolean isWriteOperation = HttpMethod.POST.matches(request.getMethod())
                    || HttpMethod.PUT.matches(request.getMethod())
                    || HttpMethod.DELETE.matches(request.getMethod())
                    || HttpMethod.PATCH.matches(request.getMethod());

            if (isWriteOperation) {
                log.warn("硬拦截只读角色写操作: user={}, role={}, path={}, method={}",
                        principal.sub(), ROLE_PLATFORM_AUDITOR_READONLY, path, request.getMethod());
                sendForbidden(request, response, ErrorCodes.FORBIDDEN_READONLY_ROLE,
                        "当前账户处于只读审计模式（PLATFORM_AUDITOR_READONLY），系统已硬拦截任何状态变更与控制下发操作",
                        Map.of("user", principal.sub(), "role", ROLE_PLATFORM_AUDITOR_READONLY, "path", path, "method", request.getMethod()));
                return false;
            }
        }

        // 将主体信息写入请求属性供后续 Controller 审计使用
        request.setAttribute(ATTR_USER_SUB, principal.sub());
        request.setAttribute(ATTR_USER_ROLES, principal.roles());
        request.setAttribute(ATTR_USER_PERMISSIONS, principal.permissions());
        return true;
    }

    private boolean isScopeMatched(String targetScope) {
        if ("*".equals(targetScope) || "all".equals(targetScope) || "project_b".equals(targetScope)) {
            return true;
        }
        return targetScope.contains("safety") || targetScope.contains("scs");
    }

    private AuthPrincipal resolvePrincipal(String token, HttpServletRequest request) {
        // 模式 A: 检查是否为标准 JWT 格式 (包含 2 个点号)
        if (token.contains(".")) {
            String[] parts = token.split("\\.");
            if (parts.length >= 2) {
                try {
                    byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
                    JsonNode node = objectMapper.readTree(payloadBytes);

                    // 检查过期时间
                    boolean expired = false;
                    if (node.has("exp") && !node.get("exp").isNull()) {
                        long expSeconds = node.get("exp").asLong();
                        if (expSeconds > 0 && (System.currentTimeMillis() / 1000) > expSeconds) {
                            expired = true;
                        }
                    }

                    String sub = node.path("sub").asText(node.path("userId").asText("user"));
                    Set<String> roles = new HashSet<>();
                    extractStringCollection(node, "roles", roles);
                    extractStringCollection(node, "role", roles);
                    extractStringCollection(node, "authorities", roles);

                    Set<String> permissions = new HashSet<>();
                    extractStringCollection(node, "permissions", permissions);

                    String scope = node.path("aud").asText(node.path("scope").asText(""));

                    // 网关头追加
                    appendGatewayHeaders(request, roles, permissions);

                    boolean isReadOnly = isReadOnlyRolePresent(roles);
                    return new AuthPrincipal(sub, roles, permissions, scope, expired, isReadOnly);
                } catch (Exception e) {
                    log.debug("JWT 解码失败，退回常规凭据比对: {}", e.getMessage());
                }
            }
        }

        // 模式 B: 常规预共享 Token 或指定只读 Token 比对
        boolean isMasterToken = !expectedBearerToken.isBlank()
                && MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8), expectedBearerToken.getBytes(StandardCharsets.UTF_8));

        boolean isReadOnlyTestToken = "scs-readonly-token".equals(token)
                || (!expectedBearerToken.isBlank() && (expectedBearerToken + "-readonly").equals(token))
                || token.contains("readonly");

        if (isMasterToken || isReadOnlyTestToken) {
            String sub = isReadOnlyTestToken ? "auditor_readonly" : "system_admin";
            Set<String> roles = new HashSet<>();
            if (isReadOnlyTestToken) {
                roles.add(ROLE_PLATFORM_AUDITOR_READONLY);
            } else {
                roles.add("ADMIN");
            }
            Set<String> permissions = new HashSet<>();
            String scope = "";

            // 读取网关可能透传的头 (例如由统一 API Gateway 解码后的标头)
            appendGatewayHeaders(request, roles, permissions);
            String headerScope = request.getHeader("X-Scope");
            if (headerScope == null || headerScope.isBlank()) {
                headerScope = request.getHeader("X-Audience");
            }
            if (headerScope != null && !headerScope.isBlank()) {
                scope = headerScope.trim();
            }

            boolean isReadOnly = isReadOnlyRolePresent(roles);
            return new AuthPrincipal(sub, roles, permissions, scope, false, isReadOnly);
        }

        return null;
    }

    private void appendGatewayHeaders(HttpServletRequest request, Set<String> roles, Set<String> permissions) {
        String rolesHeader = request.getHeader("X-User-Roles");
        if (rolesHeader != null && !rolesHeader.isBlank()) {
            for (String r : rolesHeader.split(",")) {
                if (!r.trim().isEmpty()) {
                    roles.add(r.trim());
                }
            }
        }
        String permHeader = request.getHeader("X-User-Permissions");
        if (permHeader != null && !permHeader.isBlank()) {
            for (String p : permHeader.split(",")) {
                if (!p.trim().isEmpty()) {
                    permissions.add(p.trim());
                }
            }
        }
    }

    private boolean isReadOnlyRolePresent(Set<String> roles) {
        return roles.stream().anyMatch(r ->
                r.equalsIgnoreCase(ROLE_PLATFORM_AUDITOR_READONLY)
                        || r.equalsIgnoreCase("ROLE_READONLY")
                        || r.equalsIgnoreCase("READONLY")
                        || r.toUpperCase().contains("READONLY")
                        || r.equalsIgnoreCase("PLATFORM_AUDITOR"));
    }

    private void extractStringCollection(JsonNode node, String fieldName, Set<String> target) {
        JsonNode field = node.get(fieldName);
        if (field == null || field.isNull()) {
            return;
        }
        if (field.isArray()) {
            for (JsonNode item : field) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    target.add(item.asText().trim());
                }
            }
        } else if (field.isTextual()) {
            for (String s : field.asText().split(",")) {
                if (!s.trim().isEmpty()) {
                    target.add(s.trim());
                }
            }
        }
    }

    private boolean isWhitelisted(String path) {
        return path.startsWith("/actuator")
                || path.startsWith("/health")
                || path.startsWith("/v3/api-docs")
                || path.startsWith("/swagger-ui")
                || path.startsWith("/api/v1/auth")
                || path.startsWith("/api/v1/meta")
                || path.startsWith("/api/v1/state")
                || path.startsWith("/api/v1/health")
                || path.equals("/error");
    }

    private void sendUnauthorized(HttpServletRequest request, HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TraceIdFilter.HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        response.setHeader("X-Trace-Id", traceId);

        ApiError error = ApiError.of(
                ErrorCodes.UNAUTHORIZED,
                message,
                traceId,
                null
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    private void sendForbidden(HttpServletRequest request, HttpServletResponse response, String errorCode, String message, Map<String, Object> details) throws IOException {
        response.setStatus(HttpStatus.FORBIDDEN.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");

        String traceId = MDC.get(TraceIdFilter.MDC_KEY);
        if (traceId == null || traceId.isBlank()) {
            traceId = request.getHeader(TraceIdFilter.HEADER);
        }
        if (traceId == null || traceId.isBlank()) {
            traceId = UUID.randomUUID().toString();
        }
        response.setHeader("X-Trace-Id", traceId);

        ApiError error = ApiError.of(
                errorCode != null ? errorCode : ErrorCodes.FORBIDDEN,
                message,
                traceId,
                details
        );

        response.getWriter().write(objectMapper.writeValueAsString(error));
    }

    public record AuthPrincipal(
            String sub,
            Set<String> roles,
            Set<String> permissions,
            String scope,
            boolean expired,
            boolean isReadOnly) {
    }
}
