package com.bproject.safety.module.fence;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

/** 电子围栏后端集成测试：CRUD/校验/评审/发布/版本异常重下发/停用/非法流转 409。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FenceModuleIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private FenceRepository fenceRepository;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private PersonnelRepository personnelRepository;
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
        ((InMemoryFenceRepository) fenceRepository).reset();
        ((InMemoryPersonnelRepository) personnelRepository).reset();
        ((InMemoryCollisionRepository) collisionRepository).reset();
        collisionService.resetAllPairs();
    }

    @Test
    @DisplayName("围栏列表：4 条种子，FENCE-001 已生效 4/4")
    void list() throws Exception {
        mvc.perform(get("/api/v1/fences"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(4)))
                .andExpect(jsonPath("$.list[0].status", is("已生效")))
                .andExpect(jsonPath("$.list[0].edgeSynced", is(4)));
    }

    @Test
    @DisplayName("新建围栏：顶点不足 3 个返回 422")
    void invalidPolygon() throws Exception {
        String body = "{\"name\":\"测试围栏\",\"kind\":\"临时围栏\",\"polygon\":[{\"x\":1,\"y\":1},{\"x\":2,\"y\":2}]}";
        mvc.perform(post("/api/v1/fences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("UNPROCESSABLE_ENTITY")));
    }

    @Test
    @DisplayName("新建草稿 → 提交评审 → 发布 4/4 已生效")
    void createReviewPublish() throws Exception {
        String body = "{\"name\":\"测试临时区\",\"kind\":\"临时围栏\",\"teams\":\"检修班\","
                + "\"polygon\":[{\"x\":10,\"y\":10},{\"x\":30,\"y\":10},{\"x\":30,\"y\":30},{\"x\":10,\"y\":30}]}";
        String id = mvc.perform(post("/api/v1/fences").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("草稿")))
                .andReturn().getResponse().getContentAsString()
                .replaceAll(".*\"id\":\"(FENCE-\\d+)\".*", "$1");

        mvc.perform(post("/api/v1/fences/" + id + "/submit-review"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("待评审")));

        mvc.perform(post("/api/v1/fences/" + id + "/publish").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已生效")))
                .andExpect(jsonPath("$.edgeSynced", is(4)));
    }

    @Test
    @DisplayName("已生效围栏重复提交评审返回 409")
    void conflictReview() throws Exception {
        mvc.perform(post("/api/v1/fences/FENCE-001/submit-review"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("STATE_CONFLICT")));
    }

    @Test
    @DisplayName("版本异常：EDGE-03 failed、状态版本不一致；重新下发后 4/4 已生效")
    void mismatchAndRedeliver() throws Exception {
        mvc.perform(post("/api/v1/fences/FENCE-001/simulate-mismatch"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("版本不一致")))
                .andExpect(jsonPath("$.edgeSynced", is(3)));
        mvc.perform(post("/api/v1/fences/FENCE-001/redeliver")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"nodeId\":\"EDGE-03\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已生效")))
                .andExpect(jsonPath("$.edgeSynced", is(4)));
    }

    @Test
    @DisplayName("停用围栏：已生效 → 已停用，节点回到 pending")
    void disable() throws Exception {
        mvc.perform(post("/api/v1/fences/FENCE-001/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("已停用")))
                .andExpect(jsonPath("$.edgeSynced", is(0)));
    }

    @Test
    @DisplayName("编辑围栏：PUT 更新名称")
    void update() throws Exception {
        String body = "{\"name\":\"改名后的禁区\",\"kind\":\"危险区域\",\"teams\":\"装卸一班\","
                + "\"polygon\":[{\"x\":38,\"y\":5},{\"x\":69,\"y\":5},{\"x\":69,\"y\":40},{\"x\":38,\"y\":40}]}";
        mvc.perform(put("/api/v1/fences/FENCE-002").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("改名后的禁区")));
    }
}
