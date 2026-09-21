package com.bproject.safety.module.analytics;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.rule.repository.InMemoryRuleRepository;
import com.bproject.safety.module.rule.seed.RuleDemoSeeder;
import java.time.Clock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 统计分析后端集成测试：聚合数据集、明细分页 / 下钻、设备 / 人员详情、风险突增注入与恢复。
 * 统一使用覆盖固定种子日期（2026-09-04）的自定义时间窗口，避免依赖运行当天日期。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AnalyticsModuleIntegrationTest {

    private static final String FROM = "2026-09-01T00:00:00+08:00";
    private static final String TO = "2026-09-30T23:59:59+08:00";

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private InMemoryRuleRepository ruleRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void reset() {
        ((InMemoryAlertRepository) alertRepository).clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        ruleRepository.clearDemoData();
        RuleDemoSeeder.buildSeeds().forEach(ruleRepository::save);
    }

    @Test
    @DisplayName("聚合数据集：12 条种子，KPI 总数真实为 12，风险类型 / 区域 / 等级均非空")
    void dataset() throws Exception {
        mvc.perform(get("/api/v1/analytics/dataset").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi[0].value", is("12")))
                .andExpect(jsonPath("$.riskTypes[0].count", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.areas", hasSize(greaterThanOrEqualTo(4))))
                .andExpect(jsonPath("$.levels", hasSize(4)))
                .andExpect(jsonPath("$.teams", hasSize(greaterThanOrEqualTo(3))));
    }

    @Test
    @DisplayName("事件明细分页：total=12，pageSize=5 返回 5 条")
    void eventsPaging() throws Exception {
        mvc.perform(get("/api/v1/analytics/events").param("from", FROM).param("to", TO)
                        .param("page", "1").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(12)))
                .andExpect(jsonPath("$.list", hasSize(5)));
    }

    @Test
    @DisplayName("区域下钻：装卸区 A 3 条")
    void drillByArea() throws Exception {
        mvc.perform(get("/api/v1/analytics/events").param("from", FROM).param("to", TO)
                        .param("area", "装卸区 A"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(3)));
    }

    @Test
    @DisplayName("设备详情：G-CRANE-01 聚合 2 条")
    void deviceDetail() throws Exception {
        mvc.perform(get("/api/v1/analytics/devices/G-CRANE-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.deviceId", is("G-CRANE-01")))
                .andExpect(jsonPath("$.detail.count", is(2)));
    }

    @Test
    @DisplayName("人员详情：P-1003 聚合 1 条")
    void personDetail() throws Exception {
        mvc.perform(get("/api/v1/analytics/persons/P-1003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.personId", is("P-1003")))
                .andExpect(jsonPath("$.detail.count", is(1)));
    }

    @Test
    @DisplayName("风险突增：注入 6 条后总数 18；恢复后回到 12")
    void surgeAndRestore() throws Exception {
        mvc.perform(post("/api/v1/analytics/simulate-surge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"area\":\"装卸区 A\",\"active\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active", is(true)));
        mvc.perform(get("/api/v1/analytics/dataset").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi[0].value", is("18")));
        mvc.perform(post("/api/v1/analytics/simulate-surge").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/analytics/dataset").param("from", FROM).param("to", TO))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kpi[0].value", is("12")));
    }

    @Test
    @DisplayName("重复开启突增不会重复注入（幂等）")
    void surgeIdempotent() throws Exception {
        for (int i = 0; i < 2; i++) {
            mvc.perform(post("/api/v1/analytics/simulate-surge").contentType(MediaType.APPLICATION_JSON)
                    .content("{\"active\":true}")).andExpect(status().isOk());
        }
        mvc.perform(get("/api/v1/analytics/dataset").param("from", FROM).param("to", TO))
                .andExpect(jsonPath("$.kpi[0].value", is("18")));
    }
}
