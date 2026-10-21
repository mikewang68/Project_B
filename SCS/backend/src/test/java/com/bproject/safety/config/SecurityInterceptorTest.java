package com.bproject.safety.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("C1 安全鉴权拦截器测试")
class SecurityInterceptorTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();

    @Test
    @DisplayName("安全开关关闭时(enabled=false)：所有请求直接放行")
    void disabledPassesAll() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(false, "scs-edge-key-2026", "scs-token-2026", objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/fences");
        MockHttpServletResponse response = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(request, response, new Object());

        assertThat(allowed).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("安全开关开启时：OPTIONS 预检与白名单路径直接放行")
    void enabledOptionsAndWhitelistPass() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        // OPTIONS
        MockHttpServletRequest optReq = new MockHttpServletRequest("OPTIONS", "/api/v1/fences");
        MockHttpServletResponse optResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(optReq, optResp, new Object())).isTrue();

        // /api/v1/auth/me
        MockHttpServletRequest authReq = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        MockHttpServletResponse authResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(authReq, authResp, new Object())).isTrue();

        // /api/v1/state
        MockHttpServletRequest stateReq = new MockHttpServletRequest("GET", "/api/v1/state");
        MockHttpServletResponse stateResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(stateReq, stateResp, new Object())).isTrue();

        // /actuator/health
        MockHttpServletRequest actReq = new MockHttpServletRequest("GET", "/actuator/health");
        MockHttpServletResponse actResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(actReq, actResp, new Object())).isTrue();

        // /api/v1/meta/dictionaries (供上线验证探针 verify-deployment.sh 与前端公共字典加载免密放行)
        MockHttpServletRequest metaReq = new MockHttpServletRequest("GET", "/api/v1/meta/dictionaries");
        MockHttpServletResponse metaResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(metaReq, metaResp, new Object())).isTrue();
    }

    @Test
    @DisplayName("安全开关开启时：无凭据或非法凭据拦截并返回 401 UNAUTHORIZED")
    void enabledRejectsUnauthorized() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        // 1. 无凭证
        MockHttpServletRequest req1 = new MockHttpServletRequest("GET", "/api/v1/fences");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        boolean allowed1 = interceptor.preHandle(req1, resp1, new Object());
        assertThat(allowed1).isFalse();
        assertThat(resp1.getStatus()).isEqualTo(401);
        assertThat(resp1.getContentAsString()).contains("UNAUTHORIZED");

        // 2. 错误的 API Key
        MockHttpServletRequest req2 = new MockHttpServletRequest("POST", "/api/v1/ai-events/ingest");
        req2.addHeader("X-API-Key", "invalid-key");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        boolean allowed2 = interceptor.preHandle(req2, resp2, new Object());
        assertThat(allowed2).isFalse();
        assertThat(resp2.getStatus()).isEqualTo(401);

        // 3. 错误的 Bearer Token
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req3.addHeader("Authorization", "Bearer bad-token");
        MockHttpServletResponse resp3 = new MockHttpServletResponse();
        // 3. 非法前缀 Token (scs-token-xyz 应被拦截返回 401，防止前缀绕过)
        MockHttpServletRequest req4 = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req4.addHeader("Authorization", "Bearer scs-token-xyz");
        MockHttpServletResponse resp4 = new MockHttpServletResponse();
        boolean allowed4 = interceptor.preHandle(req4, resp4, new Object());
        assertThat(allowed4).isFalse();
        assertThat(resp4.getStatus()).isEqualTo(401);
    }

    @Test
    @DisplayName("SEC-4: 启用鉴权后 /health/live 与 /health/ready 探针正常放行 (返回 200，非 401)")
    void healthEndpointsWhitelisted() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "valid-key", "valid-token", objectMapper);

        MockHttpServletRequest liveReq = new MockHttpServletRequest("GET", "/health/live");
        MockHttpServletResponse liveResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(liveReq, liveResp, new Object())).isTrue();

        MockHttpServletRequest readyReq = new MockHttpServletRequest("GET", "/health/ready");
        MockHttpServletResponse readyResp = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(readyReq, readyResp, new Object())).isTrue();
    }

    @Test
    @DisplayName("SEC-3: 鉴权开启时缺少 API Key 或 Bearer Token 启动阶段直接失败")
    void missingSecretsFailsFastOnStartup() {
        SecurityInterceptor missingKey = new SecurityInterceptor(true, "", "valid-token", objectMapper);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, missingKey::validateConfiguration);

        SecurityInterceptor missingToken = new SecurityInterceptor(true, "valid-key", "", objectMapper);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, missingToken::validateConfiguration);

        SecurityInterceptor valid = new SecurityInterceptor(true, "valid-key", "valid-token", objectMapper);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(valid::validateConfiguration);

        SecurityInterceptor disabled = new SecurityInterceptor(false, "", "", objectMapper);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(disabled::validateConfiguration);
    }

    @Test
    @DisplayName("安全开关开启时：合法的 X-API-Key 或 Bearer Token 放行")
    void enabledValidCredentialsPass() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        // 1. 合法 X-API-Key (边缘节点)
        MockHttpServletRequest req1 = new MockHttpServletRequest("POST", "/api/v1/ai-events/ingest");
        req1.addHeader("X-API-Key", "scs-edge-key-2026");
        MockHttpServletResponse resp1 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req1, resp1, new Object())).isTrue();

        // 2. 合法 Bearer Token (前端/网关)
        MockHttpServletRequest req2 = new MockHttpServletRequest("GET", "/api/v1/fences");
        req2.addHeader("Authorization", "Bearer scs-token-2026");
        MockHttpServletResponse resp2 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req2, resp2, new Object())).isTrue();
    }

    @Test
    @DisplayName("S3只读角色硬否决：PLATFORM_AUDITOR_READONLY 访问读接口放行(200)，写接口直接 403 阻断并返回脱敏 traceId")
    void readOnlyRoleAllowedOnReadBlockedOnWrite() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        // 构造包含 PLATFORM_AUDITOR_READONLY 角色的 JWT
        String readOnlyJwt = createTestJwt("auditor_01", new String[]{"PLATFORM_AUDITOR_READONLY"}, "safety", System.currentTimeMillis() / 1000 + 3600);

        // 1. 读操作：GET /api/v1/overview/summary -> 放行
        MockHttpServletRequest readReq = new MockHttpServletRequest("GET", "/api/v1/overview/summary");
        readReq.addHeader("Authorization", "Bearer " + readOnlyJwt);
        MockHttpServletResponse readResp = new MockHttpServletResponse();
        boolean readAllowed = interceptor.preHandle(readReq, readResp, new Object());
        assertThat(readAllowed).isTrue();
        assertThat(readReq.getAttribute(SecurityInterceptor.ATTR_USER_SUB)).isEqualTo("auditor_01");

        // 2. 写操作：POST /api/v1/alerts/ALT-001/handle -> 403 FORBIDDEN 硬拦截
        MockHttpServletRequest writeReq1 = new MockHttpServletRequest("POST", "/api/v1/alerts/ALT-001/handle");
        writeReq1.addHeader("Authorization", "Bearer " + readOnlyJwt);
        writeReq1.addHeader("X-Trace-Id", "9f8a3c21-test-42e1-8888-91b40e32f012");
        MockHttpServletResponse writeResp1 = new MockHttpServletResponse();
        boolean writeAllowed1 = interceptor.preHandle(writeReq1, writeResp1, new Object());

        assertThat(writeAllowed1).isFalse();
        assertThat(writeResp1.getStatus()).isEqualTo(403);
        assertThat(writeResp1.getHeader("X-Trace-Id")).isEqualTo("9f8a3c21-test-42e1-8888-91b40e32f012");
        assertThat(writeResp1.getContentAsString()).contains("FORBIDDEN_READONLY_ROLE");
        assertThat(writeResp1.getContentAsString()).contains("PLATFORM_AUDITOR_READONLY");
        assertThat(writeResp1.getContentAsString()).contains("9f8a3c21-test-42e1-8888-91b40e32f012");

        // 3. 写操作：PUT /api/v1/fences/FNC-001 -> 403 FORBIDDEN 硬拦截（测试专用只读 Token）
        MockHttpServletRequest writeReq2 = new MockHttpServletRequest("PUT", "/api/v1/fences/FNC-001");
        writeReq2.addHeader("Authorization", "Bearer scs-token-2026-readonly");
        MockHttpServletResponse writeResp2 = new MockHttpServletResponse();
        boolean writeAllowed2 = interceptor.preHandle(writeReq2, writeResp2, new Object());
        assertThat(writeAllowed2).isFalse();
        assertThat(writeResp2.getStatus()).isEqualTo(403);
        assertThat(writeResp2.getContentAsString()).contains("FORBIDDEN_READONLY_ROLE");

        // 4. 写操作：DELETE /api/v1/rules/1 -> 403 FORBIDDEN 硬拦截
        MockHttpServletRequest writeReq3 = new MockHttpServletRequest("DELETE", "/api/v1/rules/1");
        writeReq3.addHeader("Authorization", "Bearer scs-readonly-token");
        MockHttpServletResponse writeResp3 = new MockHttpServletResponse();
        boolean writeAllowed3 = interceptor.preHandle(writeReq3, writeResp3, new Object());
        assertThat(writeAllowed3).isFalse();
        assertThat(writeResp3.getStatus()).isEqualTo(403);
    }

    @Test
    @DisplayName("S3跨Scope隔离拦截：Token受众为其他系统(如 wms)时，返回 403 FORBIDDEN_SCOPE_MISMATCH")
    void crossScopeRejected() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        String wmsJwt = createTestJwt("wms_user", new String[]{"OPERATOR"}, "wms", System.currentTimeMillis() / 1000 + 3600);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req.addHeader("Authorization", "Bearer " + wmsJwt);
        req.addHeader("X-Trace-Id", "trace-cross-scope-001");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, new Object());
        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(403);
        assertThat(resp.getContentAsString()).contains("FORBIDDEN_SCOPE_MISMATCH");
    }

    @Test
    @DisplayName("S3过期Token拦截：exp在过去的Token返回 401 UNAUTHORIZED")
    void expiredTokenRejected() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        String expiredJwt = createTestJwt("user_exp", new String[]{"OPERATOR"}, "safety", System.currentTimeMillis() / 1000 - 3600);

        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req.addHeader("Authorization", "Bearer " + expiredJwt);
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, new Object());
        assertThat(allowed).isFalse();
        assertThat(resp.getStatus()).isEqualTo(401);
        assertThat(resp.getContentAsString()).contains("过期");
    }

    @Test
    @DisplayName("S3管理员写操作放行：拥有 ADMIN 角色的凭证允许执行写操作")
    void adminAllowedOnWrite() throws Exception {
        SecurityInterceptor interceptor = new SecurityInterceptor(true, "scs-edge-key-2026", "scs-token-2026", objectMapper);

        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/v1/fences");
        req.addHeader("Authorization", "Bearer scs-token-2026");
        MockHttpServletResponse resp = new MockHttpServletResponse();

        boolean allowed = interceptor.preHandle(req, resp, new Object());
        assertThat(allowed).isTrue();
    }

    private String createTestJwt(String sub, String[] roles, String scope, long expSeconds) {
        String header = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"alg\":\"none\",\"typ\":\"JWT\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        StringBuilder rolesJson = new StringBuilder("[");
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) rolesJson.append(",");
            rolesJson.append("\"").append(roles[i]).append("\"");
        }
        rolesJson.append("]");

        String payloadJson = String.format("{\"sub\":\"%s\",\"roles\":%s,\"scope\":\"%s\",\"aud\":\"%s\",\"exp\":%d}",
                sub, rolesJson, scope, scope, expSeconds);

        String payload = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString(payloadJson.getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return header + "." + payload + ".test_signature";
    }
}
