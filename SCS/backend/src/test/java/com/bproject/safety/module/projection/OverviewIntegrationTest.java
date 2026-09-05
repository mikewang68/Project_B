package com.bproject.safety.module.projection;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.collision.repository.InMemoryCollisionRepository;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.fence.repository.InMemoryFenceRepository;
import com.bproject.safety.module.personnel.repository.InMemoryPersonnelRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
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
 * 安全态势首页聚合集成测试：告警口径真实聚合、地图风险覆盖来自未关闭高风险 Alert、
 * 风险演示开关走真实存储与广播、AI 确认后分布联动。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OverviewIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private AiEventRepository aiEventRepository;
    @Autowired
    private AiDemoSeeder aiSeeder;
    @Autowired
    private PersonnelRepository personnelRepository;
    @Autowired
    private FenceRepository fenceRepository;
    @Autowired
    private CollisionRepository collisionRepository;
    @Autowired
    private CollisionService collisionService;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetSeeds() {
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clear();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        InMemoryAiEventRepository ais = (InMemoryAiEventRepository) aiEventRepository;
        ais.clear();
        aiSeeder.buildSeeds().forEach(aiEventRepository::save);
        ((InMemoryPersonnelRepository) personnelRepository).reset();
        ((InMemoryFenceRepository) fenceRepository).reset();
        ((InMemoryCollisionRepository) collisionRepository).reset();
        collisionService.resetAllPairs();
    }

    @Test
    @DisplayName("首页指标：告警为真实聚合（活动9/紧急1/严重4），AI待复核5，在岗设备为 Demo 台账")
    void summary() throws Exception {
        mvc.perform(get("/api/v1/overview/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.onDuty", is(128)))
                .andExpect(jsonPath("$.onDutyDemo", is(true)))
                .andExpect(jsonPath("$.deviceDemo", is(true)))
                .andExpect(jsonPath("$.activeAlerts", is(9)))
                .andExpect(jsonPath("$.urgentAlerts", is(1)))
                .andExpect(jsonPath("$.severeAlerts", is(4)))
                .andExpect(jsonPath("$.pendingAi", is(5)));
    }

    @Test
    @DisplayName("地图风险覆盖：赵磊存在紧急未关闭 001 → danger；关闭并解除演示后恢复 normal；演示开启再次 danger 且移动坐标")
    void mapRiskOverlayFollowsAlerts() throws Exception {
        // 初始：赵磊因 001 紧急未关闭被覆盖为 danger
        mvc.perform(get("/api/v1/overview/map"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baseDemo", is(true)))
                .andExpect(jsonPath("$.liveOverlay", is(true)))
                .andExpect(jsonPath("$.people", hasSize(7)))
                .andExpect(jsonPath("$.equipment", hasSize(4)))
                .andExpect(jsonPath("$.fences", hasSize(4)))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].state").value(org.hamcrest.Matchers.contains("danger")))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].liveOverlay").value(org.hamcrest.Matchers.contains(true)));

        // 关闭 001（处理中 → 处置 → 复核关闭）后赵磊恢复正常
        mvc.perform(post("/api/v1/alerts/ALM-20260904-001/treatment")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"measures\":[\"人员已撤离危险区域\"],\"result\":\"风险已解除\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/alerts/ALM-20260904-001/review")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"刘志明\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/overview/map"))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].state").value(org.hamcrest.Matchers.contains("normal")))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].liveOverlay").value(org.hamcrest.Matchers.contains(false)));

        // 开启风险演示：赵磊再次 danger 且坐标移动到禁区边缘
        mvc.perform(post("/api/v1/overview/simulate-risk")
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":true}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/overview/map"))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].state").value(org.hamcrest.Matchers.contains("danger")))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].x").value(org.hamcrest.Matchers.contains(57.0)))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].y").value(org.hamcrest.Matchers.contains(26.0)));
        mvc.perform(get("/api/v1/overview/summary"))
                .andExpect(jsonPath("$.activeAlerts", is(9)))
                .andExpect(jsonPath("$.urgentAlerts", is(1)));

        // 解除演示：恢复正常、紧急数回落（001 已关闭，无紧急事件）
        mvc.perform(post("/api/v1/overview/simulate-risk")
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/overview/map"))
                .andExpect(jsonPath("$.people[?(@.id=='P-ZHAO')].state").value(org.hamcrest.Matchers.contains("normal")));
        mvc.perform(get("/api/v1/overview/summary"))
                .andExpect(jsonPath("$.urgentAlerts", is(0)));
    }

    @Test
    @DisplayName("实时告警流：返回 list 包装，按时间倒序")
    void feed() throws Exception {
        mvc.perform(get("/api/v1/overview/alerts/feed").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(5)))
                .andExpect(jsonPath("$.list[0].id", is("ALM-20260904-001")))
                .andExpect(jsonPath("$.list[0].timeline", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("首页告警详情复用 Alert 权威数据，时间线完整")
    void alertDetail() throws Exception {
        mvc.perform(get("/api/v1/overview/alerts/ALM-20260904-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("ALM-20260904-001")))
                .andExpect(jsonPath("$.level", is("紧急")))
                .andExpect(jsonPath("$.timeline", hasSize(greaterThanOrEqualTo(9))));
    }

    @Test
    @DisplayName("趋势：默认 7 天 7 点，24h 为 24 点，均标记 demo")
    void trend() throws Exception {
        mvc.perform(get("/api/v1/overview/risk-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.demo", is(true)))
                .andExpect(jsonPath("$.points", hasSize(7)));
        mvc.perform(get("/api/v1/overview/risk-trend").param("range", "24h"))
                .andExpect(jsonPath("$.points", hasSize(24)));
    }

    @Test
    @DisplayName("风险分布：AI 确认违规生成 Alert 后，对应类型计数实时 +1")
    void distributionFollowsAiConfirm() throws Exception {
        Integer before = countOfType("未佩戴安全帽");
        // 026 为未佩戴安全帽，确认违规生成一条 AI 来源 Alert
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-026/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"李娜\"}"))
                .andExpect(status().isOk());
        Integer after = countOfType("未佩戴安全帽");
        org.assertj.core.api.Assertions.assertThat(after).isEqualTo(before + 1);
    }

    private Integer countOfType(String type) throws Exception {
        org.springframework.test.web.servlet.MvcResult r = mvc.perform(get("/api/v1/overview/risk-distribution"))
                .andExpect(status().isOk()).andReturn();
        java.util.List<java.util.Map<String, Object>> items =
                com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.items");
        return items.stream()
                .filter(m -> type.equals(m.get("type")))
                .map(m -> ((Number) m.get("count")).intValue())
                .findFirst().orElse(0);
    }
}
