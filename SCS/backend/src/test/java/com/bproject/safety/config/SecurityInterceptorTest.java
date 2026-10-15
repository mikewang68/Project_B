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
        boolean allowed3 = interceptor.preHandle(req3, resp3, new Object());
        assertThat(allowed3).isFalse();
        assertThat(resp3.getStatus()).isEqualTo(401);
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

        // 3. 动态前缀 Token (scs-token-...)
        MockHttpServletRequest req3 = new MockHttpServletRequest("GET", "/api/v1/alerts");
        req3.addHeader("Authorization", "Bearer scs-token-usr001");
        MockHttpServletResponse resp3 = new MockHttpServletResponse();
        assertThat(interceptor.preHandle(req3, resp3, new Object())).isTrue();
    }
}
