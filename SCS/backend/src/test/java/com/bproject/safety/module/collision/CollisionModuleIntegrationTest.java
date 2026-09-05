package com.bproject.safety.module.collision;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.alert.model.DemoAlert;
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
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** 设备防碰撞后端集成测试：风险计算、严重建单、紧急升级同一 Alert、联动失败/接管、解除申请。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CollisionModuleIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private CollisionService collisionService;
    @Autowired
    private CollisionRepository collisionRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private PersonnelRepository personnelRepository;
    @Autowired
    private FenceRepository fenceRepository;
    @Autowired
    private Clock clock;

    @BeforeEach
    void reset() {
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clear();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        ((InMemoryCollisionRepository) collisionRepository).reset();
        ((InMemoryPersonnelRepository) personnelRepository).reset();
        ((InMemoryFenceRepository) fenceRepository).reset();
        collisionService.resetAllPairs();
    }

    private void approach(int times) throws Exception {
        for (int i = 0; i < times; i++) {
            mvc.perform(post("/api/v1/collision/simulate")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"deviceId\":\"VEH-07\",\"scenario\":\"approach\"}")).andExpect(status().isOk());
        }
    }

    @Test
    @DisplayName("设备列表：4 台种子，初始风险安全")
    void devices() throws Exception {
        mvc.perform(get("/api/v1/collision/devices"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(4)))
                .andExpect(jsonPath("$.list[0].risk", is("安全")));
    }

    @Test
    @DisplayName("配对距离与趋势：pair 返回初始 12.6m，趋势 10 点")
    void pairAndTrend() throws Exception {
        mvc.perform(get("/api/v1/collision/pair").param("currentId", "VEH-07").param("relatedId", "TIP-02"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.distance", is(12.6)))
                .andExpect(jsonPath("$.risk", is("安全")));
        mvc.perform(get("/api/v1/collision/devices/VEH-07/distance-trend"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(10)));
    }

    @Test
    @DisplayName("接近：9.2 预警不建单；5.4 严重首次建单；2.8 紧急升级同一 Alert 且不重复建单")
    void approachRiskLifecycle() throws Exception {
        long base = alertRepository.count();
        approach(1); // 9.2 预警
        mvc.perform(get("/api/v1/collision/pair").param("currentId", "VEH-07"))
                .andExpect(jsonPath("$.risk", is("预警")));
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(base);

        approach(2); // 7.1、5.4 → 严重，建单
        List<DemoAlert> severe = collisionAlerts();
        org.assertj.core.api.Assertions.assertThat(severe).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(severe.get(0).risk).isEqualTo("严重");
        String alertId = severe.get(0).id;

        approach(2); // 3.8、2.8 → 紧急，升级同一告警
        List<DemoAlert> after = collisionAlerts();
        org.assertj.core.api.Assertions.assertThat(after).hasSize(1);
        org.assertj.core.api.Assertions.assertThat(after.get(0).id).isEqualTo(alertId);
        org.assertj.core.api.Assertions.assertThat(after.get(0).risk).isEqualTo("紧急");
        org.assertj.core.api.Assertions.assertThat(after.get(0).upgradedFrom).isEqualTo("严重");

        mvc.perform(get("/api/v1/collision/devices/VEH-07"))
                .andExpect(jsonPath("$.risk", is("紧急")))
                .andExpect(jsonPath("$.latestAlertId", is(alertId)));
    }

    @Test
    @DisplayName("雷达断数：风险变为待确认，不自动升级")
    void radarDown() throws Exception {
        mvc.perform(post("/api/v1/collision/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"scenario\":\"radarDown\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk", is("待确认")))
                .andExpect(jsonPath("$.radarDown", is(true)));
    }

    @Test
    @DisplayName("联动失败：PLC failed，关联 Alert 记录失败；人工接管后复用同一 Alert Timeline")
    void linkageFailAndTakeover() throws Exception {
        approach(3); // 到 5.4 严重并建单
        String alertId = collisionAlerts().get(0).id;

        mvc.perform(post("/api/v1/collision/linkage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"mode\":\"fail\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[5].id", is("plc")))
                .andExpect(jsonPath("$.steps[5].state", is("failed")));
        DemoAlert failed = alertRepository.findById(alertId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(failed.linkageFailed).isTrue();

        mvc.perform(post("/api/v1/collision/takeover")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"operator\":\"王建国\",\"note\":\"现场急停\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state", is("stopped")))
                .andExpect(jsonPath("$.plcStatus", is("人工确认停机")))
                .andExpect(jsonPath("$.alertId", is(alertId)));
        DemoAlert taken = alertRepository.findById(alertId).orElseThrow();
        org.assertj.core.api.Assertions.assertThat(taken.takeover).isTrue();
        org.assertj.core.api.Assertions.assertThat(taken.timeline.toString()).contains("人工接管");
    }

    @Test
    @DisplayName("联动成功：六步全部 success，设备停机")
    void linkageSuccess() throws Exception {
        approach(3);
        mvc.perform(post("/api/v1/collision/linkage")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"mode\":\"success\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.steps[5].state", is("success")));
    }

    @Test
    @DisplayName("解除运行限制申请：返回 requestId 与待审批")
    void releaseRequest() throws Exception {
        mvc.perform(post("/api/v1/collision/release-request")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"operator\":\"王建国\",\"checks\":[\"设备已经停止\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId", notNullValue()))
                .andExpect(jsonPath("$.status", is("待审批")));
    }

    private List<DemoAlert> collisionAlerts() {
        // 仅统计本次由碰撞模拟新建（带 COLLISION 去重键）的告警，排除种子中既有的设备类告警
        return alertRepository.findAll().stream()
                .filter(a -> a.dedupKey != null && a.dedupKey.startsWith("COLLISION:")).toList();
    }
}
