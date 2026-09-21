package com.bproject.safety.module.ai;

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
import org.springframework.test.web.servlet.MvcResult;

/**
 * AI 违规识别后端集成测试：列表/指标/复核流转/确认违规生成 Alert/误报不建 Alert/派单/模拟/摄像头。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiModuleIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private AiEventRepository aiEventRepository;
    @Autowired
    private AiDemoSeeder aiSeeder;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetSeeds() {
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        InMemoryAiEventRepository ais = (InMemoryAiEventRepository) aiEventRepository;
        ais.clearDemoData();
        aiSeeder.buildSeeds().forEach(aiEventRepository::save);
    }

    @Test
    @DisplayName("AI 列表：10 条种子 + 指标口径 26/5/12/9/2 + 后端分页")
    void listAndMetrics() throws Exception {
        mvc.perform(get("/api/v1/ai-events").param("pageSize", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total", is(10)))
                .andExpect(jsonPath("$.list", hasSize(5)))
                .andExpect(jsonPath("$.pageSize", is(5)))
                .andExpect(jsonPath("$.metrics.today", is(26)))
                .andExpect(jsonPath("$.metrics.pending", is(5)))
                .andExpect(jsonPath("$.metrics.confirmed", is(12)))
                .andExpect(jsonPath("$.metrics.falsePositive", is(9)))
                .andExpect(jsonPath("$.metrics.cameraFault", is(2)))
                .andExpect(jsonPath("$.facets.areas", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("AI 详情：检测框/模型/时间线齐全")
    void detail() throws Exception {
        mvc.perform(get("/api/v1/ai-events/AI-E-20260903-026"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("未佩戴安全帽")))
                .andExpect(jsonPath("$.confidence", is(94.8)))
                .andExpect(jsonPath("$.model", is("PPE-Detection-v2.4.1")))
                .andExpect(jsonPath("$.boxes", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.timeline", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.linkedAlertId").doesNotExist());
    }

    @Test
    @DisplayName("确认违规：创建关联 Alert（alert.new），相同幂等键重复确认不重复建 Alert")
    void confirmCreatesAlertOnce() throws Exception {
        long before = alertRepository.findAll().size();

        MvcResult first = mvc.perform(post("/api/v1/ai-events/AI-E-20260903-026/confirm")
                        .header("Idempotency-Key", "idem-ai-confirm-026")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"李娜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已确认违规")))
                .andExpect(jsonPath("$.linkedAlertId", notNullValue()))
                .andReturn();
        long afterFirst = alertRepository.findAll().size();
        org.assertj.core.api.Assertions.assertThat(afterFirst).isEqualTo(before + 1);
        String linkedId = com.jayway.jsonpath.JsonPath.read(first.getResponse().getContentAsString(), "$.linkedAlertId");

        // 测试环境无 Kvrocks：重复 confirm 会被状态机拦截为 409，同样不会重复建 Alert
        // （相同 Idempotency-Key 的回放路径由 AiEventRealtimeTest 单元覆盖）
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-026/confirm")
                        .header("Idempotency-Key", "idem-ai-confirm-026-again")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"李娜\"}"))
                .andExpect(status().isConflict());
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll().size()).isEqualTo(afterFirst);

        // 新 Alert 来源为 AI违规、状态待确认
        mvc.perform(get("/api/v1/alerts/" + linkedId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.source", is("AI违规")))
                .andExpect(jsonPath("$.status", is("待确认")));
    }

    @Test
    @DisplayName("标记误报：记录原因，不创建 Alert")
    void falsePositiveCreatesNoAlert() throws Exception {
        long before = alertRepository.findAll().size();
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-023/false-positive")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"遮挡误判\",\"reviewer\":\"李娜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("误报")))
                .andExpect(jsonPath("$.falseReason", is("遮挡误判")))
                .andExpect(jsonPath("$.linkedAlertId").doesNotExist());
        org.assertj.core.api.Assertions.assertThat(alertRepository.findAll().size()).isEqualTo(before);
    }

    @Test
    @DisplayName("暂不确定：进入人工复核队列，不创建 Alert")
    void uncertain() throws Exception {
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-022/uncertain")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"李娜\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("不确定")));
    }

    @Test
    @DisplayName("非法状态流转返回 409 STATE_CONFLICT")
    void illegalTransitionIs409() throws Exception {
        // 018 初始为误报，不允许再确认
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-018/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("STATE_CONFLICT")));
    }

    @Test
    @DisplayName("AI 派单：无关联 Alert 时先创建 Alert 并直接派发到待处理，AI 事件置已派单")
    void assignCreatesAndDispatchesAlert() throws Exception {
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-025/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"王建国\",\"assigneeId\":\"USR-002\",\"priority\":\"普通\",\"note\":\"尽快处理\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已派单")))
                .andExpect(jsonPath("$.assignee", is("王建国")))
                .andExpect(jsonPath("$.linkedAlertId", notNullValue()));
        // 关联 Alert 已处于待处理
        MvcResult r = mvc.perform(get("/api/v1/ai-events/AI-E-20260903-025")).andReturn();
        String linkedId = com.jayway.jsonpath.JsonPath.read(r.getResponse().getContentAsString(), "$.linkedAlertId");
        mvc.perform(get("/api/v1/alerts/" + linkedId))
                .andExpect(jsonPath("$.status", is("待处理")))
                .andExpect(jsonPath("$.assignee", is("王建国")));
    }

    @Test
    @DisplayName("处理中 → 关闭：AI 事件可关闭")
    void processAndClose() throws Exception {
        // 017 初始已派单 → 处理中 → 已关闭
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-017/process")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("处理中")));
        mvc.perform(post("/api/v1/ai-events/AI-E-20260903-017/close")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已关闭")));
    }

    @Test
    @DisplayName("模拟事件：new 新增待复核且不建 Alert；low-confidence 为不确定 61%；camera-fault 摄像头异常")
    void simulateKinds() throws Exception {
        mvc.perform(post("/api/v1/ai-events/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"new\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待复核")))
                .andExpect(jsonPath("$.fresh", is(true)))
                .andExpect(jsonPath("$.linkedAlertId").doesNotExist())
                .andExpect(jsonPath("$.camera", is("CAM-07")));

        mvc.perform(post("/api/v1/ai-events/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"low-confidence\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("不确定")))
                .andExpect(jsonPath("$.confidence", is(61.0)));

        mvc.perform(post("/api/v1/ai-events/simulate")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"kind\":\"camera-fault\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("摄像头异常")));

        mvc.perform(get("/api/v1/ai-events")).andExpect(jsonPath("$.total", is(13)));
    }

    @Test
    @DisplayName("摄像头台账：8 路，CAM-02/CAM-03 画面质量下降")
    void cameras() throws Exception {
        mvc.perform(get("/api/v1/cameras"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(8)))
                .andExpect(jsonPath("$[0].cameraId", notNullValue()));
    }
}
