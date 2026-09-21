package com.bproject.safety.module.projection;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/**
 * 大屏 / 移动端投影集成测试：全部聚合自同一 Alert 内存源，无独立数据表。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProjectionIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AlertRepository repository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void resetSeeds() {
        InMemoryAlertRepository mem = (InMemoryAlertRepository) repository;
        mem.clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
    }

    @Test
    @DisplayName("大屏总览：告警为真实统计，设备/在岗标记为 Demo 聚合")
    void screenOverview() throws Exception {
        mvc.perform(get("/api/v1/screen/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.todayAlerts", is(12)))
                .andExpect(jsonPath("$.urgentAlerts", is(1)))
                .andExpect(jsonPath("$.onDuty", is(128)))
                .andExpect(jsonPath("$.onDutyDemo", is(true)))
                .andExpect(jsonPath("$.deviceDemo", is(true)))
                .andExpect(jsonPath("$.deviceHealth", org.hamcrest.Matchers.hasSize(4)))
                .andExpect(jsonPath("$.riskTypeDistribution[0].count", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.riskTrend", org.hamcrest.Matchers.hasSize(24)));
    }

    @Test
    @DisplayName("大屏事件流：按时间倒序，字段为 Alert 投影")
    void screenFeed() throws Exception {
        mvc.perform(get("/api/v1/screen/alerts/feed").param("limit", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id", is("ALM-20260904-001")))
                .andExpect(jsonPath("$[0].objectName", notNullValue()))
                .andExpect(jsonPath("$", org.hamcrest.Matchers.hasSize(5)));
    }

    @Test
    @DisplayName("大屏紧急事件：初始为 001；升级为紧急后计数增加；001 处置关闭后 critical 切换到 009")
    void screenCriticalFollowsAlerts() throws Exception {
        mvc.perform(get("/api/v1/screen/critical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("ALM-20260904-001")));

        // 009（预警）升级为紧急 → 紧急计数增加
        mvc.perform(post("/api/v1/alerts/ALM-20260904-009/escalate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"level\":\"紧急\",\"reason\":\"风险升高\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/screen/overview"))
                .andExpect(jsonPath("$.urgentAlerts", is(2)));

        // 001 初始为处理中：提交处置 → 复核关闭，critical 切换到仍未关闭的 009
        mvc.perform(post("/api/v1/alerts/ALM-20260904-001/treatment")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"measures\":[\"人员已撤离危险区域\"],\"result\":\"风险已解除\"}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/v1/alerts/ALM-20260904-001/review")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reviewer\":\"刘志明\"}"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/screen/critical"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", is("ALM-20260904-009")));
    }

    @Test
    @DisplayName("移动首页：待接单=待处理，处置中=处理中+已升级，已关闭计数")
    void mobileHome() throws Exception {
        mvc.perform(get("/api/v1/mobile/home"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userName", is("王建国")))
                .andExpect(jsonPath("$.pending", is(1)))
                .andExpect(jsonPath("$.handling", is(3)))
                .andExpect(jsonPath("$.urgent", is(1)))
                .andExpect(jsonPath("$.closedToday", is(3)))
                .andExpect(jsonPath("$.pendingItems[0].id", is("ALM-20260904-005")));
    }

    @Test
    @DisplayName("移动端接单/到场：主状态保持待处理，mobileStage 逐步推进，未接单先到场报 409")
    void mobileAcceptArriveFlow() throws Exception {
        // 002 待确认 → 待派单 → 待处理
        mvc.perform(post("/api/v1/alerts/ALM-20260904-002/confirm")
                .contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isOk());
        mvc.perform(post("/api/v1/alerts/ALM-20260904-002/assign")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"assignee\":\"王建国\",\"assigneeId\":\"USR-002\",\"priority\":\"普通\",\"limitMin\":15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待处理")))
                .andExpect(jsonPath("$.mobileStage", is("PENDING")));

        // 未接单先到场 → 409
        mvc.perform(post("/api/v1/mobile/incidents/ALM-20260904-002/arrive")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict());

        // 接单：主状态不变，stage=ACCEPTED
        mvc.perform(post("/api/v1/mobile/incidents/ALM-20260904-002/accept")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"handler\":\"王建国\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待处理")))
                .andExpect(jsonPath("$.mobileStage", is("ACCEPTED")))
                .andExpect(jsonPath("$.acceptedAt", notNullValue()));

        // 到场：stage=ARRIVED
        mvc.perform(post("/api/v1/mobile/incidents/ALM-20260904-002/arrive")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"handler\":\"王建国\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mobileStage", is("ARRIVED")))
                .andExpect(jsonPath("$.arrivedAt", notNullValue()));

        // 开始处理（复用 /alerts start）：主状态→处理中，stage=PROCESSING
        mvc.perform(post("/api/v1/alerts/ALM-20260904-002/start")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("处理中")))
                .andExpect(jsonPath("$.mobileStage", is("PROCESSING")));
    }
}
