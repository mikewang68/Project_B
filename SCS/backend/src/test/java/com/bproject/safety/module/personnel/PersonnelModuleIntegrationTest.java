package com.bproject.safety.module.personnel;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** 人员定位后端集成测试：列表/详情/轨迹/实时位置/异常模拟/越界建 Alert 与去重。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class PersonnelModuleIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AlertRepository alertRepository;
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
    void reset() {
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clear();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        ((InMemoryPersonnelRepository) personnelRepository).reset();
        ((InMemoryFenceRepository) fenceRepository).reset();
        ((InMemoryCollisionRepository) collisionRepository).reset();
        collisionService.resetAllPairs();
    }

    @Test
    @DisplayName("人员列表：7 条种子 + stats 在线/异常/手环离线/低电量")
    void listAndStats() throws Exception {
        mvc.perform(get("/api/v1/personnel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(7)))
                .andExpect(jsonPath("$.stats.online", is(6)))
                .andExpect(jsonPath("$.stats.bandOffline", is(1)))
                .andExpect(jsonPath("$.stats.lowBattery", greaterThanOrEqualTo(1)));
    }

    @Test
    @DisplayName("人员详情与轨迹：赵磊详情 + 9 个轨迹点")
    void detailAndTrack() throws Exception {
        mvc.perform(get("/api/v1/personnel/P-ZHAO"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("赵磊")))
                .andExpect(jsonPath("$.braceletId", is("WB-018")));
        mvc.perform(get("/api/v1/personnel/P-ZHAO/track"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", hasSize(9)));
    }

    @Test
    @DisplayName("实时位置 live：仅返回在线人员")
    void live() throws Exception {
        mvc.perform(get("/api/v1/personnel/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(6)));
    }

    @Test
    @DisplayName("不存在人员返回 404")
    void notFound() throws Exception {
        mvc.perform(get("/api/v1/personnel/NOBODY"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("NOT_FOUND")));
    }

    @Test
    @DisplayName("低电量模拟：赵磊电量降到 8、手环低电量")
    void lowBattery() throws Exception {
        mvc.perform(post("/api/v1/personnel/simulate-abnormal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"P-ZHAO\",\"kind\":\"lowBattery\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.battery", is(8)))
                .andExpect(jsonPath("$.braceletStatus", is("低电量")));
    }

    @Test
    @DisplayName("越界：进入危险围栏生成人员越界 Alert，且重复触发不重复建单")
    void intrusionCreatesAlertOnce() throws Exception {
        long before = alertRepository.count();
        // 第一次越界：新增 1 条人员安全告警
        mvc.perform(post("/api/v1/personnel/simulate-abnormal")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"P-ZHAO\",\"kind\":\"intrusion\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.risk", is("高风险")))
                .andExpect(jsonPath("$.activeAlertIds", hasSize(1)));
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(before + 1);
        String alertId = alertRepository.findAll().stream()
                .filter(a -> "人员越界".equals(a.eventType)).findFirst().orElseThrow().id;

        // 第二次越界：命中去重键，复用同一 Alert，不新增
        mvc.perform(post("/api/v1/personnel/simulate-abnormal")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"id\":\"P-ZHAO\",\"kind\":\"intrusion\"}"))
                .andExpect(status().isOk());
        org.assertj.core.api.Assertions.assertThat(alertRepository.count()).isEqualTo(before + 1);
        // 人员相关告警接口可查到该告警
        mvc.perform(get("/api/v1/personnel/P-ZHAO/alerts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].id", is(alertId)))
                .andExpect(jsonPath("$.list[0].source", is("人员安全")));
    }

    @Test
    @DisplayName("手环提醒返回 sent=true")
    void remind() throws Exception {
        mvc.perform(post("/api/v1/personnel/P-ZHAO/remind")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"撤离\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sent", is(true)))
                .andExpect(jsonPath("$.time", notNullValue()));
    }
}
