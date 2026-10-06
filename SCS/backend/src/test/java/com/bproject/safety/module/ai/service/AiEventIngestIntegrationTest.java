package com.bproject.safety.module.ai.service;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.module.ai.dto.AiRequests.AiIngestRequest;
import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
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

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AiEventIngestIntegrationTest {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private AiEventRepository aiEventRepository;
    @Autowired
    private AiDemoSeeder aiSeeder;
    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        if (aiEventRepository instanceof InMemoryAiEventRepository inMem) {
            inMem.clearDemoData();
            aiSeeder.buildSeeds().forEach(aiEventRepository::save);
        }
    }

    @Test
    @DisplayName("AI 事件上报：带目标检测画框、置信度、时间线生成成功")
    void testIngestSuccess_WithFullBoxesAndConfidence() throws Exception {
        AiIngestRequest request = new AiIngestRequest(
                "未佩戴安全帽",
                "CAM-01",
                "装卸区 1 号枪机",
                "装卸区 A",
                96.8,
                80.0,
                1.5,
                "YOLOv8-PPE-v1.0",
                "HIGH",
                "helmet",
                "RULE-PPE-01",
                "连续 1.5 秒未检测到安全帽，置信度 96.8%",
                "/snapshots/20261006/cam01_helmet_violation.jpg",
                List.of(
                        new AiBox("box-1", "PERSON", 98.2, 35.0, 40.0, 20.0, 45.0, "person"),
                        new AiBox("box-2", "NO HELMET", 96.8, 40.0, 41.0, 10.0, 12.0, "violation")
                ),
                "现场作业人员 A",
                "CAM-01",
                OffsetDateTime.now()
        );

        mvc.perform(post("/api/v1/ai-events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id", startsWith("AI-E-")))
                .andExpect(jsonPath("$.type", is("未佩戴安全帽")))
                .andExpect(jsonPath("$.camera", is("CAM-01")))
                .andExpect(jsonPath("$.area", is("装卸区 A")))
                .andExpect(jsonPath("$.confidence", is(96.8)))
                .andExpect(jsonPath("$.status", is("待复核")))
                .andExpect(jsonPath("$.risk", is("高")))
                .andExpect(jsonPath("$.scene", is("helmet")))
                .andExpect(jsonPath("$.boxes", hasSize(2)))
                .andExpect(jsonPath("$.boxes[0].label", is("PERSON")))
                .andExpect(jsonPath("$.boxes[1].label", is("NO HELMET")))
                .andExpect(jsonPath("$.timeline", hasSize(2)))
                .andExpect(jsonPath("$.timeline[0].text", containsString("AI 边缘视觉引擎检测到违规行为")))
                .andExpect(jsonPath("$.timeline[1].text", containsString("进入人工复核队列")));

        // 验证列表中可查询到
        mvc.perform(get("/api/v1/ai-events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].type", is("未佩戴安全帽")));
    }

    @Test
    @DisplayName("AI 越界闯入事件上报：包含危险区域边界与人体框")
    void testIngestIntrusion_WithZoneBox() throws Exception {
        AiIngestRequest request = new AiIngestRequest(
                "危险区域越界闯入",
                "CAM-03",
                "铁路线 3 号球机",
                "铁路线 B 警戒区",
                92.5,
                85.0,
                2.0,
                "YOLOv8-Intrusion-v1.0",
                null, // 自动推导为 HIGH
                "intrusion",
                "RULE-FENCE-INTRUSION",
                "人员进入危险区域且滞留超过 2 秒",
                "/snapshots/20261006/cam03_intrusion.jpg",
                List.of(
                        new AiBox("box-p1", "PERSON", 95.0, 50.0, 55.0, 18.0, 40.0, "person"),
                        new AiBox("box-z1", "DANGER ZONE", null, 10.0, 30.0, 80.0, 60.0, "zone")
                ),
                null,
                null,
                null
        );

        mvc.perform(post("/api/v1/ai-events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.type", is("危险区域越界闯入")))
                .andExpect(jsonPath("$.risk", is("高")))
                .andExpect(jsonPath("$.boxes", hasSize(2)))
                .andExpect(jsonPath("$.boxes[1].tone", is("zone")));
    }

    @Test
    @DisplayName("AI 事件上报：空请求体返回 400 校验异常")
    void testIngestEmptyBody_BadRequest() throws Exception {
        mvc.perform(post("/api/v1/ai-events/ingest")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(""))
                .andExpect(status().isBadRequest());
    }
}
