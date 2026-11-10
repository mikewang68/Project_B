package com.bproject.safety.module.ai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 边缘 AI 真机推理客户端：调用 edge-ai 服务 POST /api/v1/simulate/trigger
 * （合成巡检帧 + 真实 YOLO PPE/闯入检测）。任何失败（连接拒绝/超时/非 200/解析异常）
 * 返回 empty，由调用方平滑降级为本地模拟推理（与 {@code AiEventService.triggerEdge} 同策略）。
 *
 * <p>{@code report_to_scs=false}：/infer 是纯计算演示，不向 AI 复核列表注水；
 * 事件升级仍走页面 /escalate 显式链路。</p>
 */
@Component
public class EdgeAiInferenceClient {

    private static final Logger log = LoggerFactory.getLogger(EdgeAiInferenceClient.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(1500))
            .build();

    @Value("${scs.edge-ai.url:http://127.0.0.1:18090}")
    private String edgeAiUrl;

    public EdgeAiInferenceClient(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** 边缘返回的单个检测框（x/y/w/h 为 0-100 百分比坐标；score 缺省时无置信度）。 */
    public record EdgeBox(String id, String label, Double score,
                          double x, double y, double w, double h, String tone) {
    }

    /** 边缘返回的单条违规判定（type 为中文违规类型，与 ingest eventType 同词表）。 */
    public record EdgeViolation(String type, double confidence, String rule, String judge) {
    }

    /** 一次成功的边缘推理结果。 */
    public record EdgeInference(String edgeScenario, String snapshotUrl,
                                List<EdgeBox> boxes, List<EdgeViolation> violations, long latencyMs) {
    }

    /**
     * 触发边缘真机推理。cameraCode 仅透传给边缘（快照归属），不上报 SCS。
     */
    public Optional<EdgeInference> infer(String edgeScenario, String cameraCode) {
        String base = (edgeAiUrl == null || edgeAiUrl.isBlank())
                ? "http://127.0.0.1:18090" : edgeAiUrl.trim();
        long start = System.nanoTime();
        try {
            Map<String, Object> payload = Map.of(
                    "scenario", edgeScenario,
                    "camera_code", cameraCode == null || cameraCode.isBlank() ? "CAM-01" : cameraCode,
                    "report_to_scs", false);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(base + "/api/v1/simulate/trigger"))
                    .timeout(Duration.ofSeconds(6))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                log.warn("Edge AI inference returned {} from {}", response.statusCode(), base);
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            List<EdgeBox> boxes = new ArrayList<>();
            for (JsonNode box : root.path("boxes")) {
                boxes.add(new EdgeBox(box.path("id").asText(), box.path("label").asText(),
                        box.hasNonNull("score") ? box.path("score").asDouble() : null,
                        box.path("x").asDouble(), box.path("y").asDouble(),
                        box.path("w").asDouble(), box.path("h").asDouble(), box.path("tone").asText()));
            }
            List<EdgeViolation> violations = new ArrayList<>();
            for (JsonNode violation : root.path("violations")) {
                violations.add(new EdgeViolation(violation.path("type").asText(),
                        violation.path("confidence").asDouble(),
                        violation.path("rule").asText(), violation.path("judge").asText()));
            }
            long latencyMs = (System.nanoTime() - start) / 1_000_000L;
            return Optional.of(new EdgeInference(edgeScenario,
                    root.hasNonNull("snapshot_url") ? root.path("snapshot_url").asText() : null,
                    boxes, violations, latencyMs));
        } catch (Exception e) {
            log.warn("Edge AI inference unavailable at {}: {}", base, e.getMessage());
            return Optional.empty();
        }
    }
}
