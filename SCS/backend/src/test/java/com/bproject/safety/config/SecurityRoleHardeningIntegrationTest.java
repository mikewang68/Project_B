package com.bproject.safety.config;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.infrastructure.health.ReadinessAggregator;
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
        "app.security.api-key=scs-edge-key-2026",
        "app.security.bearer-token=scs-token-2026"
})
@DisplayName("S3 角色级权限与只读硬否决端到端集成测试")
class SecurityRoleHardeningIntegrationTest {

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
    @DisplayName("只读审计角色(PLATFORM_AUDITOR_READONLY)访问只读接口放行，返回 200 OK")
    void readOnlyAuditorCanAccessReadEndpoint() throws Exception {
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer scs-token-2026-readonly"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onDuty").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    @DisplayName("只读审计角色访问写操作接口被服务端硬拦截，返回 403 FORBIDDEN_READONLY_ROLE 与 traceId")
    void readOnlyAuditorBlockedOnWriteEndpointWith403() throws Exception {
        mockMvc.perform(post("/api/v1/fences")
                        .header("Authorization", "Bearer scs-token-2026-readonly")
                        .header("X-Trace-Id", "trace-auditor-reject-001")
                        .contentType("application/json")
                        .content("{\"name\":\"TestFence\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_READONLY_ROLE"))
                .andExpect(jsonPath("$.traceId").value("trace-auditor-reject-001"))
                .andExpect(header().string("X-Trace-Id", "trace-auditor-reject-001"));
    }

    @Test
    @DisplayName("跨 Scope 越权访问被拦截，返回 403 FORBIDDEN_SCOPE_MISMATCH")
    void crossScopeBlockedWith403() throws Exception {
        mockMvc.perform(get("/api/v1/overview/summary")
                        .header("Authorization", "Bearer scs-token-2026")
                        .header("X-Scope", "wms"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN_SCOPE_MISMATCH"))
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }
}
