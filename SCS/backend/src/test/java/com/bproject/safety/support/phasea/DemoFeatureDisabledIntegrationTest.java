package com.bproject.safety.support.phasea;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

/**
 * F-11：app.demo.seed-enabled=false / simulator-enabled=false 时，
 * 启动不灌 Demo 种子，模拟端点返回 403 DEMO_FEATURE_DISABLED（生产安全默认）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "app.demo.seed-enabled=false",
        "app.demo.simulator-enabled=false"
})
class DemoFeatureDisabledIntegrationTest {

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

    @Test
    @DisplayName("关闭 seed：各 InMemory 仓库启动后保持为空，不隐式灌 Demo 数据")
    void noSeedWhenDisabled() {
        assertThat(alertRepository.findAll()).isEmpty();
        assertThat(personnelRepository.findAll()).isEmpty();
        assertThat(fenceRepository.findAll()).isEmpty();
        assertThat(collisionRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("关闭 simulator：模拟端点返回 403 DEMO_FEATURE_DISABLED，而不是 200 静默成功")
    void simulateRejectedWhenDisabled() throws Exception {
        mvc.perform(post("/api/v1/collision/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"deviceId\":\"VEH-07\",\"scenario\":\"approach\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("DEMO_FEATURE_DISABLED"));
    }
}
