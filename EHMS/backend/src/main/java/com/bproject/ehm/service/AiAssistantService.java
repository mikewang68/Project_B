package com.bproject.ehm.service;

import com.bproject.ehm.domain.Alarm;
import com.bproject.ehm.domain.Device;
import com.bproject.ehm.repository.AlarmRepository;
import com.bproject.ehm.repository.DeviceRepository;
import com.bproject.ehm.repository.WorkOrderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class AiAssistantService {
    private final DeviceRepository devices;
    private final AlarmRepository alarms;
    private final WorkOrderRepository workOrders;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;

    public AiAssistantService(DeviceRepository devices,
                              AlarmRepository alarms,
                              WorkOrderRepository workOrders,
                              ObjectMapper objectMapper,
                              @Value("${ehm.ai.enabled:false}") boolean enabled,
                              @Value("${ehm.ai.base-url:}") String baseUrl,
                              @Value("${ehm.ai.api-key:}") String apiKey,
                              @Value("${ehm.ai.model:}") String model,
                              @Value("${ehm.ai.timeout-seconds:60}") long timeoutSeconds) {
        this.devices = devices;
        this.alarms = alarms;
        this.workOrders = workOrders;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(Math.min(timeoutSeconds, 30))).build();
    }

    public AssistantResponse answer(String message) {
        String cleanMessage = message == null ? "" : message.trim();
        if (cleanMessage.isEmpty()) {
            return new AssistantResponse("请输入需要分析的问题。", "validation", Instant.now());
        }

        String context = buildContext(cleanMessage);
        if (externalAiReady()) {
            try {
                return new AssistantResponse(callOpenAiCompatible(cleanMessage, context), "external-api", Instant.now());
            } catch (Exception exception) {
                return new AssistantResponse(fallbackAnswer(cleanMessage) + "\n\n外部AI接口暂不可用，本次已自动降级为规则解释。",
                        "rule-fallback", Instant.now());
            }
        }
        return new AssistantResponse(fallbackAnswer(cleanMessage), "rule-demo", Instant.now());
    }

    private boolean externalAiReady() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }

    private String callOpenAiCompatible(String message, String context) throws Exception {
        String endpoint = baseUrl.endsWith("/chat/completions")
                ? baseUrl : baseUrl.replaceAll("/$", "") + "/chat/completions";
        Map<String, Object> payload = Map.of(
                "model", model,
                "temperature", 0.2,
                "messages", List.of(
                        Map.of("role", "system", "content", "你是设备健康管理系统的运维辅助助手。只依据提供的演示数据回答；区分事实、候选原因和建议；不得声称直接控制PLC；重大结论必须提醒人工复核。"),
                        Map.of("role", "system", "content", context),
                        Map.of("role", "user", "content", message)
                )
        );
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(60))
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("AI接口返回HTTP " + response.statusCode());
        }
        JsonNode root = objectMapper.readTree(response.body());
        String answer = root.at("/choices/0/message/content").asText("");
        if (answer.isBlank()) throw new IllegalStateException("AI接口未返回有效内容");
        return answer;
    }

    private String buildContext(String message) {
        String code = detectDeviceCode(message).orElse("GT-01");
        Device device = devices.findById(code).orElse(null);
        List<Alarm> deviceAlarms = alarms.findAll().stream()
                .filter(alarm -> code.equals(alarm.deviceCode()) && !"已关闭".equals(alarm.status()))
                .toList();
        return "当前演示设备=" + (device == null ? "未找到" : device)
                + "；活动告警=" + deviceAlarms
                + "；全场工单数=" + workOrders.count() + "。";
    }

    private String fallbackAnswer(String message) {
        if (containsAny(message, "RUL", "寿命", "预测")) {
            return "RUL预测当前未启用。现阶段已保存设备健康分和趋势字段，但还缺少足够的退化样本、故障/更换标签及经过验证的推理模型。建议先使用规则和趋势预警，不能给出伪精确剩余寿命。";
        }
        if (containsAny(message, "检查", "处置", "建议")) {
            return "建议按以下顺序处理：1.核验传感器安装、接线、量程和校准；2.对齐设备工况与载荷窗口；3.复测关键测点并补充油样或现场照片；4.由设备工程师确认候选原因；5.确认维护窗口后创建工单。系统不直接下发PLC控制指令。";
        }
        Optional<String> code = detectDeviceCode(message);
        if (code.isPresent()) {
            Device device = devices.findById(code.get()).orElse(null);
            if (device == null) return "未找到设备“" + code.get() + "”，请确认设备编码。";
            long activeAlarms = alarms.findAll().stream()
                    .filter(alarm -> device.code().equals(alarm.deviceCode()) && !"已关闭".equals(alarm.status()))
                    .count();
            return device.code() + "（" + device.name() + "）当前健康分为"
                    + (device.health() == null ? "不可评估" : device.health())
                    + "，风险状态为“" + device.risk() + "”，活动告警" + activeAlarms
                    + "项，主要事项为“" + device.alarm() + "”。这是演示数据，候选原因需要现场复核。";
        }
        return "当前Demo已接入设备、告警和工单数据。你可以询问GT-01等设备风险、生成现场检查项或询问RUL启用条件。未配置外部AI接口时，系统使用可审计的固定规则回答。";
    }

    private Optional<String> detectDeviceCode(String message) {
        return devices.findAll().stream()
                .map(Device::code)
                .filter(message::contains)
                .findFirst();
    }

    private boolean containsAny(String value, String... candidates) {
        for (String candidate : candidates) if (value.contains(candidate)) return true;
        return false;
    }

    public record AssistantResponse(String answer, String mode, Instant answeredAt) {
    }
}
