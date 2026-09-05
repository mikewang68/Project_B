package com.bproject.safety.controller;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.infrastructure.health.ReadinessAggregator;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebLayerTest {
    @Autowired MockMvc mockMvc;
    @MockBean ReadinessAggregator readinessAggregator;

    private ReadinessAggregator.ReadinessReport report(String status) {
        Map<String, String> components = new LinkedHashMap<>();
        components.put("application", "UP");
        components.put("database", "UP");
        components.put("cache", "UP");
        components.put("rocketmq", "DISABLED");
        components.put("openGemini", "DISABLED");
        return new ReadinessAggregator.ReadinessReport(status, components);
    }

    @Test
    void liveReturnsUp() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void readyAggregatesComponentsAnd200WhenUp() throws Exception {
        when(readinessAggregator.aggregate()).thenReturn(report("UP"));
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components.database").value("UP"))
                .andExpect(jsonPath("$.components.rocketmq").value("DISABLED"));
    }

    @Test
    void readyReturns503WhenDown() throws Exception {
        when(readinessAggregator.aggregate()).thenReturn(report("DOWN"));
        mockMvc.perform(get("/health/ready")).andExpect(status().isServiceUnavailable());
    }

    @Test
    void currentUserMatchesInventoryContract() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value("USR-001"))
                .andExpect(jsonPath("$.name").value("李娜"))
                .andExpect(jsonPath("$.role").value("安全员"))
                .andExpect(jsonPath("$.team").value("安全管理组"))
                .andExpect(jsonPath("$.shift").value("夜班"))
                .andExpect(jsonPath("$.online").value(true));
    }

    @Test
    void dictionariesReturnAllByDefault() throws Exception {
        mockMvc.perform(get("/api/v1/meta/dictionaries"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas.length()").value(6))
                .andExpect(jsonPath("$.teams.length()").value(4))
                .andExpect(jsonPath("$.assignees.length()").value(4))
                .andExpect(jsonPath("$.assignees[0].id").exists());
    }

    @Test
    void dictionariesFilterByKeys() throws Exception {
        mockMvc.perform(get("/api/v1/meta/dictionaries").param("keys", "areas,teams"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas.length()").value(6))
                .andExpect(jsonPath("$.teams.length()").value(4))
                .andExpect(jsonPath("$.assignees").doesNotExist());
    }

    @Test
    void incomingTraceIdIsEchoed() throws Exception {
        mockMvc.perform(get("/health/live").header("X-Trace-Id", "trace-abc-123"))
                .andExpect(header().string("X-Trace-Id", "trace-abc-123"));
    }

    @Test
    void missingTraceIdIsGenerated() throws Exception {
        mockMvc.perform(get("/health/live"))
                .andExpect(header().string("X-Trace-Id",
                        org.hamcrest.Matchers.matchesPattern("[0-9a-fA-F-]{36}")));
    }

    @Test
    void unknownPathReturnsUnifiedErrorBody() throws Exception {
        mockMvc.perform(get("/api/v1/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.traceId").exists())
                .andExpect(header().exists("X-Trace-Id"));
    }

    @Test
    void wrongMethodReturns405() throws Exception {
        mockMvc.perform(post("/health/live")).andExpect(status().isMethodNotAllowed());
    }

    @Test
    void unsafeTraceIdIsReplaced() throws Exception {
        // 含空格等非法字符的 TraceId 不应被原样回显
        mockMvc.perform(get("/health/live").header("X-Trace-Id", "bad trace id"))
                .andExpect(header().string("X-Trace-Id",
                        org.hamcrest.Matchers.not("bad trace id")));
    }
}
