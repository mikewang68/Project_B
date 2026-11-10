package com.bproject.safety.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceRequest;
import com.bproject.safety.module.ai.dto.AiModelDtos.AiInferenceResult;
import com.bproject.safety.module.ai.dto.AiModelDtos.DetectedObjectBox;
import com.bproject.safety.module.ai.repository.InMemoryAiModelRepository;
import com.bproject.safety.module.alert.service.AlertService;
import java.time.Clock;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * /ai/models/infer 边缘真机推理接入：有边缘结果走真推（engineUsed=EDGE-AI）、
 * 边缘不可用或场景无边缘模型时降级本地模拟（行为与旧版一致）。
 */
class AiModelEdgeInferenceTest {

    private AiModelService serviceWith(EdgeAiInferenceClient client) {
        AiModelService service = new AiModelService(
                new InMemoryAiModelRepository(), mock(AlertService.class),
                mock(DomainLivePublisher.class), Clock.systemUTC());
        service.setEdgeAiInferenceClient(client);
        return service;
    }

    @Test
    @DisplayName("边缘在线：ppe_violation 走真机推理，框/违规/引擎口径与本地模拟对齐")
    void edgeResultMapped() {
        EdgeAiInferenceClient client = new EdgeAiInferenceClientStub(Optional.of(new EdgeAiInferenceClient.EdgeInference(
                "no_helmet", "/api/v1/ai-events/snapshots/snap-x.jpg",
                List.of(
                        new EdgeAiInferenceClient.EdgeBox("no-helmet-1", "NO HELMET", 96.8, 12.5, 3.0, 8.0, 11.0, "violation"),
                        new EdgeAiInferenceClient.EdgeBox("person-1", "PERSON", 98.0, 10.0, 2.0, 12.0, 45.0, "person"),
                        new EdgeAiInferenceClient.EdgeBox("zone-PIT-01", "DANGER ZONE", null, 35.0, 35.0, 50.0, 55.0, "zone")),
                List.of(new EdgeAiInferenceClient.EdgeViolation("未佩戴安全帽", 96.8, "RULE-PPE-HELMET",
                        "边缘监控检测：作业人员未佩戴符合标准的安全帽 (置信度 96.8%)")),
                1234L)));
        AiModelService service = serviceWith(client);

        AiInferenceResult result = service.runInference(new AiInferenceRequest(null, "ppe_violation", null, null, 45.0));

        assertThat(result.engineUsed()).startsWith("EDGE-AI");
        assertThat(result.summary()).startsWith("【边缘真机推理】");
        assertThat(result.imageSource()).isEqualTo("/api/v1/ai-events/snapshots/snap-x.jpg");
        assertThat(result.latencyMs()).isEqualTo(1234.0);
        assertThat(result.violationCount()).isEqualTo(1);
        assertThat(result.riskLevel()).isEqualTo("WARNING");

        DetectedObjectBox violationBox = result.detectedObjects().stream()
                .filter(DetectedObjectBox::isViolation).findFirst().orElseThrow();
        assertThat(violationBox.labelZh()).isEqualTo("未佩戴安全帽");
        assertThat(violationBox.note()).contains("未佩戴符合标准的安全帽");
        assertThat(violationBox.x()).isEqualTo(12.5);
        assertThat(violationBox.width()).isEqualTo(8.0);

        // 无分数的 zone 框保留（score=null 不做阈值过滤），person 框为正常目标
        assertThat(result.detectedObjects()).hasSize(3);
        DetectedObjectBox zone = result.detectedObjects().stream()
                .filter(b -> "DANGER ZONE".equals(b.label())).findFirst().orElseThrow();
        assertThat(zone.isViolation()).isFalse();
    }

    @Test
    @DisplayName("边缘离线：降级本地模拟推理，summary 前缀与引擎标识不变")
    void edgeUnavailableFallsBackToSimulation() {
        AiModelService service = serviceWith(new EdgeAiInferenceClientStub(Optional.empty()));

        AiInferenceResult result = service.runInference(new AiInferenceRequest(null, "ppe_violation", null, null, null));

        assertThat(result.summary()).startsWith("【本地模拟推理】");
        assertThat(result.engineUsed()).doesNotContain("EDGE-AI");
        assertThat(result.detectedObjects()).isNotEmpty();
    }

    @Test
    @DisplayName("无边缘模型的场景（fire_smoke）：即使客户端在位也走本地模拟")
    void scenarioWithoutEdgeModelStaysLocal() {
        EdgeAiInferenceClient client = new EdgeAiInferenceClientStub(Optional.of(new EdgeAiInferenceClient.EdgeInference(
                "unused", null, List.of(), List.of(), 5L)));
        AiModelService service = serviceWith(client);

        AiInferenceResult result = service.runInference(new AiInferenceRequest(null, "fire_smoke", null, null, null));

        assertThat(result.summary()).startsWith("【本地模拟推理】");
        assertThat(result.engineUsed()).doesNotContain("EDGE-AI");
    }

    /** 桩客户端：不发起网络请求。 */
    private static class EdgeAiInferenceClientStub extends EdgeAiInferenceClient {
        private final Optional<EdgeInference> answer;

        EdgeAiInferenceClientStub(Optional<EdgeInference> answer) {
            super(new com.fasterxml.jackson.databind.ObjectMapper());
            this.answer = answer;
        }

        @Override
        public Optional<EdgeInference> infer(String edgeScenario, String cameraCode) {
            return answer;
        }
    }
}
