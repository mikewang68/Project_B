package com.bproject.safety;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.infrastructure.mq.RocketMqAdapter;
import com.bproject.safety.infrastructure.timeseries.OpenGeminiClient;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 全上下文冒烟测试：在没有真实 openGauss/Kvrocks/RocketMQ/openGemini 的 CI 环境中
 * 应用必须能正常启动（探针报 DOWN/DISABLED，而不是启动失败）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApplicationContextSmokeTest {
    @Autowired ApplicationContext context;
    @Autowired MockMvc mockMvc;

    @Test
    void contextLoadsWithoutRealInfrastructure() {
        assertThat(context.getBean(SafetyApplication.class)).isNotNull();
        // test profile 下 RocketMQ/openGemini 未启用，Adapter 不应被创建
        assertThat(context.getBeanProvider(RocketMqAdapter.class).getIfAvailable()).isNull();
        assertThat(context.getBean(OpenGeminiClient.class).isEnabled()).isFalse();
    }

    @Test
    void liveAndDemoEndpointsWork() throws Exception {
        mockMvc.perform(get("/health/live")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/api/v1/auth/me")).andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("李娜"));
        mockMvc.perform(get("/api/v1/meta/dictionaries").param("keys", "areas"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.areas.length()").value(12));
    }

    @Test
    void readyReflectsUnreachableInfrastructure() throws Exception {
        // test profile 指向不可达端口：database/cache=DOWN，整体 503；未启用组件=DISABLED
        mockMvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andExpect(jsonPath("$.components.database").value("DOWN"))
                .andExpect(jsonPath("$.components.cache").value("DOWN"))
                .andExpect(jsonPath("$.components.rocketmq").value("DISABLED"))
                .andExpect(jsonPath("$.components.openGemini").value("DISABLED"));
    }

    @Test
    void openApiDocsAvailable() throws Exception {
        mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk())
                .andExpect(jsonPath("$.paths./health/live.get").exists())
                .andExpect(jsonPath("$.paths./api/v1/auth/me.get").exists());
    }

    @Test
    void actuatorHealthStillPresent() throws Exception {
        // test profile 下 openGauss/Kvrocks 不可达，Actuator 聚合状态为 DOWN/503 属预期
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"));
    }
}
