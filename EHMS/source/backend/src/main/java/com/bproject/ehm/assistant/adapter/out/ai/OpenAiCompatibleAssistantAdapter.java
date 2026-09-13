package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Component
public class OpenAiCompatibleAssistantAdapter implements AssistantModelPort {
    private static final Logger log = LoggerFactory.getLogger(OpenAiCompatibleAssistantAdapter.class);

    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;
    private final boolean enabled;
    private final String baseUrl;
    private final String apiKey;
    private final String model;
    private final Duration requestTimeout;

    public OpenAiCompatibleAssistantAdapter(ObjectMapper objectMapper,
                                            @Value("${ehm.ai.enabled:false}") boolean enabled,
                                            @Value("${ehm.ai.base-url:}") String baseUrl,
                                            @Value("${ehm.ai.api-key:}") String apiKey,
                                            @Value("${ehm.ai.model:}") String model,
                                            @Value("${ehm.ai.timeout-seconds:60}") long timeoutSeconds) {
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
        this.model = model;
        this.requestTimeout = Duration.ofSeconds(Math.max(1, timeoutSeconds));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(Math.min(Math.max(1, timeoutSeconds), 30)))
                .build();
    }

    @Override
    public Optional<String> answer(String message, String context) {
        if (!ready()) return Optional.empty();
        try {
            String endpoint = baseUrl.endsWith("/chat/completions")
                    ? baseUrl : baseUrl.replaceAll("/$", "") + "/chat/completions";
            Map<String, Object> payload = Map.of(
                    "model", model,
                    "temperature", 0.2,
                    "messages", List.of(
                            Map.of("role", "system", "content", "你是设备健康管理系统的运维辅助助手。只依据提供的数据回答；区分事实、候选原因和建议；不得声称直接控制PLC；重大结论必须提醒人工复核。"),
                            Map.of("role", "system", "content", context),
                            Map.of("role", "user", "content", message)
                    )
            );
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .header("Authorization", "Bearer " + apiKey)
                    .header("Content-Type", "application/json")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.warn("AI接口返回HTTP {}", response.statusCode());
                return Optional.empty();
            }
            JsonNode root = objectMapper.readTree(response.body());
            String answer = root.at("/choices/0/message/content").asText("").trim();
            return answer.isBlank() ? Optional.empty() : Optional.of(answer);
        } catch (Exception exception) {
            log.warn("AI适配器调用失败，已降级为本地规则：{}", exception.getMessage());
            return Optional.empty();
        }
    }

    private boolean ready() {
        return enabled && !baseUrl.isBlank() && !apiKey.isBlank() && !model.isBlank();
    }
}
