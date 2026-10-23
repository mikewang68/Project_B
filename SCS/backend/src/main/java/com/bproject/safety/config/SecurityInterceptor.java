package com.bproject.safety.config;

import com.bproject.safety.common.error.ApiError;
import com.bproject.safety.common.error.ErrorCodes;
import com.bproject.safety.common.web.TraceIdFilter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
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
 * 安全鉴权与权限拦截器（API-Key、验签 JWT、IAM 服务端验票）。
 *
 * <p>安全边界（G4 修复后）：
 *
 * <ol>
 *   <li>机器凭据：{@code X-API-Key} 常量时间比较，仅用于边缘节点上报。
 *   <li>用户凭据：{@code Authorization: Bearer <JWT>} 必须通过 {@link JwtVerifier} 验签，
 *       算法白名单 HS256/384/512，校验 {@code exp}/{@code nbf}、可选 {@code iss}/{@code aud}。
 *       签名不通过、{@code alg=none}、过期、受众不符一律 401。
 *   <li>SSO 票据：{@code X-SSO-Ticket} 交由 {@link ScsIamTicketValidator} 调 IAM 原子消费验票，
 *       主体与范围由 IAM 返回，不再信任前端自述 claims。
 *   <li>跨 Scope 隔离：主体受众必须落在 safety/scs，否则 403 FORBIDDEN_SCOPE_MISMATCH。
 *   <li>只读审计角色 {@code PLATFORM_AUDITOR_READONLY}：读放行，写操作前置硬拦截 403。
 * </ol>
 *
 * <p>已删除的生产后门：固定 {@code bearer-token} 直通、{@code "scs-readonly-token"} 固定只读票、
 * {@code token.contains("readonly")} 子串判定、以及由请求头 {@code X-User-Roles} 提升权限。
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
    private final JwtVerifier jwtVerifier;
    private final ScsIamTicketValidator ticketValidator;
    private final boolean jwtSecretConfigured;
    private final String jwtIssuerConfigured;
    @org.springframework.beans.factory.annotation.Autowired private ScsLocalPolicy localPolicy;

    public SecurityInterceptor(
            @Value("${app.security.enabled:true}") boolean enabled,
            @Value("${app.security.api-key:}") String expectedApiKey,
            @Value("${app.security.bearer-token:}") String expectedBearerToken,
            @Value("${app.security.jwt-secret:}") String jwtSecret,
            @Value("${app.security.jwt-issuer:}") String jwtIssuer,
            @Value("${app.security.jwt-clock-skew-seconds:60}") long clockSkewSeconds,
            @Value("${app.security.iam-base-url:}") String iamBaseUrl,
            @Value("${app.security.iam-service-token:}") String iamServiceToken,
            @Value("${app.security.iam-audience:scs}") String iamAudience,
            @Value("${app.security.iam-timeout-ms:3000}") long iamTimeoutMs,
            ObjectMapper objectMapper) {
        this.enabled = enabled;
        this.expectedApiKey = expectedApiKey != null ? expectedApiKey.trim() : "";
        // 保留该配置项只为显式拒绝：任何静态令牌都不再被接受。
        this.expectedBearerToken = expectedBearerToken != null ? expectedBearerToken.trim() : "";
        this.objectMapper = objectMapper;
        this.ticketValidator =
                new ScsIamTicketValidator(iamBaseUrl, iamServiceToken, iamAudience, iamTimeoutMs, objectMapper);
        // 受众（aud）不在此处硬校验：跨系统受众由 finish() 统一返回 403 FORBIDDEN_SCOPE_MISMATCH，
        // 避免把"身份合法但受众不符"误报成签名失败。
        // issuer 不允许选配：只要启用了 JWT 通道，就必须能校验签发方。
        this.jwtSecretConfigured = jwtSecret != null && !jwtSecret.isBlank();
        this.jwtIssuerConfigured = jwtIssuer == null ? "" : jwtIssuer.trim();
        this.jwtVerifier =
                jwtSecret == null || jwtSecret.isBlank()
                        ? null
                        : new JwtVerifier(jwtSecret, jwtIssuer, clockSkewSeconds, objectMapper);
    }

    @PostConstruct
    public void validateConfiguration() {
        if (!enabled) {
            return;
        }
        if (expectedApiKey.isBlank()) {
            throw new IllegalStateException(
                    "Security is enabled (app.security.enabled=true) but app.security.api-key (APP_SECURITY_API_KEY) is not configured. Startup rejected.");
        }
        if (jwtSecretConfigured && jwtIssuerConfigured.isBlank()) {
            throw new IllegalStateException(
                    "app.security.jwt-secret 已配置但 app.security.jwt-issuer 为空："
                            + "拒绝在无法校验签发方的情况下启用 JWT 验签（fail-closed）。");
        }
        if (jwtVerifier == null && !ticketValidator.isUsable()) {
            throw new IllegalStateException(
                    "Security is enabled but neither app.security.jwt-secret nor the IAM ticket validator "
                            + "(app.security.iam-base-url + iam-service-token + iam-audience) is configured. "
                            + "Refusing to start without an authentication path (fail-closed).");
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

        // 2b. 本地会话（G6）：票据已一次性换成会话，后续请求不再要求携带票据。
        //     撤销检查优先于一切：即使会话还在，IAM 侧注销后也必须拒绝。
        HttpSession existing = request.getSession(false);
        if (existing != null && Boolean.TRUE.equals(existing.getAttribute("SCS_SSO_ESTABLISHED"))) {
            var binding=ScsSessionRegistry.get(existing.getId());
            if(binding==null || ScsSessionRegistry.isSessionRevoked(existing.getId())){sendUnauthorized(request,response,"业务会话绑定缺失或已撤销");return false;}
            try{
                var actor=localPolicy.resolve(ticketValidator.current(binding.iamSubject(),binding.iamSid()));
                if(!localPolicy.permits(actor,request.getMethod(),path)){
                    sendForbidden(request,response,ErrorCodes.FORBIDDEN_READONLY_ROLE,"当前业务操作未获授权",Map.of());return false;
                }
                if(!Set.of("GET","HEAD","OPTIONS").contains(request.getMethod())){
                    String csrf=request.getHeader("X-SCS-CSRF"),expected=(String)existing.getAttribute("SCS_CSRF");
                    if(csrf==null||expected==null||!MessageDigest.isEqual(csrf.getBytes(StandardCharsets.UTF_8),expected.getBytes(StandardCharsets.UTF_8))){
                        sendForbidden(request,response,ErrorCodes.FORBIDDEN_READONLY_ROLE,"CSRF校验失败",Map.of());return false;
                    }
                }
                request.setAttribute("SCS_ACTOR",actor);
                if (path.equals("/api/v1/auth/sso/logout")) {
                    request.setAttribute(ATTR_USER_SUB, actor.subject());
                    return true;
                }
                return finish(new AuthPrincipal(actor.subject(),actor.roles(),actor.permissions(),"scs",false,actor.readOnly()),request,response,path,"native SSO");
            }catch(ScsIamTicketValidator.InvalidTicketException denied){sendUnauthorized(request,response,"统一身份或业务映射不可用");return false;}
        }
        // 3. 边缘节点机器凭据检查 (X-API-Key，常量时间等值比较)
        String apiKey = request.getHeader("X-API-Key");
        if (apiKey != null && !apiKey.isBlank() && !expectedApiKey.isBlank()
                && MessageDigest.isEqual(apiKey.getBytes(StandardCharsets.UTF_8), expectedApiKey.getBytes(StandardCharsets.UTF_8))) {
            if(!path.startsWith("/api/v1/ingest/") && !path.equals("/api/v1/ai-events/ingest")){
                sendForbidden(request,response,ErrorCodes.FORBIDDEN_READONLY_ROLE,"机器凭据不用于业务操作",Map.of());return false;
            }
            request.setAttribute(ATTR_USER_SUB, "edge-node");
            request.setAttribute(ATTR_USER_ROLES, Set.of("ROLE_EDGE"));
            return true;
        }

        // 5. 用户 / 网关 Bearer Token 凭证检查
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

        // 静态 bearer-token 已废弃：配了就显式拒绝，避免部署方误以为仍可用。
        if (!expectedBearerToken.isBlank()
                && MessageDigest.isEqual(rawToken.getBytes(StandardCharsets.UTF_8), expectedBearerToken.getBytes(StandardCharsets.UTF_8))) {
            log.warn("拒绝静态 bearer-token 使用（G4 已移除该通道）: path={}", path);
            sendUnauthorized(request, response, "访问受限：静态口令通道已停用，请使用受签名的 JWT 或 SSO 票据");
            return false;
        }

        AuthPrincipal principal;
        try {
            principal = resolvePrincipal(rawToken);
        } catch (JwtVerifier.InvalidTokenException e) {
            log.warn("拒绝未授权访问（验签失败）: path={}, reason={}", path, e.getMessage());
            // 过期单独提示，便于前端触发重新登录；其余原因统一为签名不可信。
            String message = e.getMessage() != null && e.getMessage().contains("过期")
                    ? "访问受限：身份令牌已过期，请重新登录换发"
                    : "访问受限：凭据不匹配或签名验证失败";
            sendUnauthorized(request, response, message);
            return false;
        }
        if (principal == null) {
            log.warn("拒绝未授权访问（凭据不匹配或验签失败）: path={}, method={}, remoteAddr={}", path, request.getMethod(), request.getRemoteAddr());
            sendUnauthorized(request, response, "访问受限：凭据不匹配或签名验证失败");
            return false;
        }

        return finish(principal, request, response, path, "JWT");
    }

    /** 受众隔离 + 只读写拦截 + 主体属性下发。 */
    private boolean finish(
            AuthPrincipal principal,
            HttpServletRequest request,
            HttpServletResponse response,
            String path,
            String credentialKind) throws IOException {
        if (principal.expired()) {
            log.warn("拒绝访问（Token 已过期）: path={}, user={}", path, principal.sub());
            sendUnauthorized(request, response, "访问受限：身份令牌已过期，请重新登录换发");
            return false;
        }

        // 跨 Scope / 受众隔离检查
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

        // 只读审计角色（PLATFORM_AUDITOR_READONLY）写操作硬否决检查
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

        request.setAttribute(ATTR_USER_SUB, principal.sub());
        request.setAttribute(ATTR_USER_ROLES, principal.roles());
        request.setAttribute(ATTR_USER_PERMISSIONS, principal.permissions());
        log.debug("鉴权通过: user={}, kind={}, path={}, method={}", principal.sub(), credentialKind, path, request.getMethod());
        return true;
    }

    private boolean isScopeMatched(String targetScope) {
        if ("*".equals(targetScope) || "all".equals(targetScope) || "project_b".equals(targetScope)) {
            return true;
        }
        return targetScope.contains("safety") || targetScope.contains("scs");
    }

    /**
     * 仅接受验签通过的 JWT。验签失败的 token 直接返回 null（调用方 401），
     * 不再退回任何按前缀/子串匹配的静态口令。
     */
    private AuthPrincipal resolvePrincipal(String token) {
        if (jwtVerifier == null) {
            log.warn("收到 Bearer Token 但未配置 JWT 验签密钥，拒绝");
            return null;
        }
        JsonNode claims = jwtVerifier.verify(token);

        // 验签器已按 exp/nbf（含时钟偏移）拒绝，这里保留二次布尔判断作为防御性冗余。
        boolean expired = false;
        if (claims.hasNonNull("exp")) {
            long expSeconds = claims.get("exp").asLong();
            if (expSeconds > 0 && (System.currentTimeMillis() / 1000) > expSeconds) {
                expired = true;
            }
        }

        String sub = claims.path("sub").asText(claims.path("userId").asText(""));
        if (sub.isBlank()) {
            log.warn("JWT 缺少 sub，拒绝");
            return null;
        }

        Set<String> roles = new HashSet<>();
        extractStringCollection(claims.get("roles"), roles);
        extractStringCollection(claims.get("role"), roles);
        extractStringCollection(claims.get("authorities"), roles);

        Set<String> permissions = new HashSet<>();
        extractStringCollection(claims.get("permissions"), permissions);

        String scope = claims.path("aud").isArray()
                ? (claims.path("aud").size() > 0 ? claims.path("aud").get(0).asText("") : "")
                : claims.path("aud").asText(claims.path("scope").asText(""));
        if (scope.isBlank()) {
            // 没有受众就无法做跨系统隔离，按凭据不完整处理。
            log.warn("JWT 缺少 aud/scope，拒绝: sub={}", sub);
            return null;
        }

        return new AuthPrincipal(sub, roles, permissions, scope, expired, isReadOnlyRolePresent(roles));
    }

    private boolean isReadOnlyRolePresent(Set<String> roles) {
        return roles.stream().anyMatch(r ->
                r.equalsIgnoreCase(ROLE_PLATFORM_AUDITOR_READONLY)
                        || r.equalsIgnoreCase("ROLE_READONLY")
                        || r.equalsIgnoreCase("READONLY")
                        || r.equalsIgnoreCase("PLATFORM_AUDITOR"));
    }

    private void extractStringCollection(JsonNode field, Set<String> target) {
        if (field == null || field.isNull() || field.isMissingNode()) {
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
                || Set.of("/api/v1/auth/sso/exchange","/api/v1/auth/sso/revoke","/api/v1/auth/sso/health").contains(path)
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
