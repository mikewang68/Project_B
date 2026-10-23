package com.bproject.safety.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.infrastructure.health.ReadinessAggregator;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.security.enabled=true",
        "app.security.api-key=offline-unit-only-ingestion-key-never-deploy",
        "app.security.jwt-secret=scs-it-hs256-secret-0123456789abcdefghijklmn",
        "app.security.jwt-issuer=iam"
})
@DisplayName("G4 角色级权限、验签与只读硬否决端到端集成测试")
class SecurityRoleHardeningIntegrationTest {

    private static final String JWT_SECRET = "scs-it-hs256-secret-0123456789abcdefghijklmn";

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private ReadinessAggregator readinessAggregator;

    @Test
    @DisplayName("匿名访问业务端点被拦截，返回 401 UNAUTHORIZED 与 traceId")
    void anonymousRequestReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/fences"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("alg=none 的伪造 JWT 被拒绝（旧实现按 payload 信任角色）")
    void algNoneTokenRejected() throws Exception {
        String forged = b64("{\"alg\":\"none\",\"typ\":\"JWT\"}") + "."
                + b64("{\"sub\":\"attacker\",\"roles\":[\"ADMIN\"],\"aud\":\"safety\",\"exp\":"
                        + (now() + 3600) + "}") + ".";
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("静态 scs-readonly-token 不再被接受")
    void staticReadonlyTokenRejected() throws Exception {
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer scs-readonly-token"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("只读审计角色(PLATFORM_AUDITOR_READONLY)访问只读接口放行，返回 200 OK")
    void readOnlyAuditorCanAccessReadEndpoint() throws Exception {
        String jwt = jwt("auditor_01", new String[]{"PLATFORM_AUDITOR_READONLY"}, "safety", now() + 3600);
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onDuty").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("只读审计角色访问写操作接口被服务端硬拦截，返回 403 FORBIDDEN_READONLY_ROLE 与 traceId")
    void readOnlyAuditorBlockedOnWriteEndpointWith403() throws Exception {
        String jwt = jwt("auditor_01", new String[]{"PLATFORM_AUDITOR_READONLY"}, "safety", now() + 3600);
        mockMvc.perform(post("/api/v1/fences")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-Trace-Id", "trace-auditor-reject-001")
                        .contentType("application/json")
                        .content("{\"name\":\"TestFence\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_READONLY_ROLE"))
                .andExpect(jsonPath("$.traceId").value("trace-auditor-reject-001"))
                .andExpect(header().string("X-Trace-Id", "trace-auditor-reject-001"));
    }

    @Test
    @DisplayName("跨 Scope 越权访问被拦截，返回 403 FORBIDDEN_SCOPE_MISMATCH（验签通过、受众不符）")
    void crossScopeBlockedWith403() throws Exception {
        String jwt = jwt("wms_user", new String[]{"OPERATOR"}, "wms", now() + 3600);
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer " + jwt))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE_MISMATCH"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("ADMIN 合法 JWT 可执行写操作（业务层可能 4xx，但不得是 401/403）")
    void adminTokenNotRejectedByInterceptor() throws Exception {
        String jwt = jwt("admin_01", new String[]{"ADMIN"}, "safety", now() + 3600);
        int httpStatus = mockMvc.perform(post("/api/v1/fences")
                        .header("Authorization", "Bearer " + jwt)
                        .header("X-Trace-Id", "trace-admin-allow-001")
                        .contentType("application/json")
                        .content("{\"name\":\"TestFence\"}"))
                .andReturn().getResponse().getStatus();
        org.assertj.core.api.Assertions.assertThat(httpStatus).isNotIn(401, 403);
    }

    // ---- helpers ----

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String b64(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String jwt(String sub, String[] roles, String aud, long exp) {
        StringBuilder rolesJson = new StringBuilder("[");
        for (int i = 0; i < roles.length; i++) {
            if (i > 0) {
                rolesJson.append(",");
            }
            rolesJson.append("\"").append(roles[i]).append("\"");
        }
        rolesJson.append("]");
        String payload = "{\"sub\":\"" + sub + "\",\"iss\":\"iam\",\"aud\":\"" + aud
                + "\",\"roles\":" + rolesJson + ",\"exp\":" + exp + "}";

        String signingInput = b64("{\"alg\":\"HS256\",\"typ\":\"JWT\"}") + "." + b64(payload);
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(JWT_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            String sig = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(mac.doFinal(signingInput.getBytes(StandardCharsets.US_ASCII)));
            return signingInput + "." + sig;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
