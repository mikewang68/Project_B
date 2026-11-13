package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.application.AiConfigService;
import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class OpenAiCompatibleAssistantAdapter implements AssistantModelPort {
    private final ObjectMapper mapper;
    private final HttpClient client;
    private final boolean enabled;
    private final String baseUrl, apiKey, keyFile, model, policy;
    private final String provider, trustedHttpOrigin;
    private final boolean toolCallsEnabled;
    private final Duration timeout;
    private final AiConfigService config;

    @Autowired
    public OpenAiCompatibleAssistantAdapter(ObjectMapper mapper,
            @Value("${ehm.ai.enabled:false}") boolean enabled,
            @Value("${ehm.ai.base-url:https://api.deepseek.com}") String baseUrl,
            @Value("${ehm.ai.api-key:}") String apiKey,
            @Value("${ehm.ai.api-key-file:}") String keyFile,
            @Value("${ehm.ai.model:deepseek-flash}") String model,
            @Value("${ehm.ai.data-policy:disabled}") String policy,
            @Value("${ehm.ai.timeout-seconds:45}") long seconds,
            @Value("${ehm.ai.provider:}") String provider,
            @Value("${ehm.ai.trusted-http-origin:}") String trustedHttpOrigin,
            @Value("${ehm.ai.tools-enabled:true}") boolean toolCallsEnabled,
            ObjectProvider<AiConfigService> configProvider) {
        this(mapper, enabled, baseUrl, apiKey, keyFile, model, policy, seconds,
                provider, trustedHttpOrigin, toolCallsEnabled, configProvider.getIfAvailable());
    }

    // Retained for existing transports/tests; HTTP origins are denied by default.
    public OpenAiCompatibleAssistantAdapter(ObjectMapper mapper, boolean enabled, String baseUrl,
            String apiKey, String keyFile, String model, String policy, long seconds) {
        this(mapper, enabled, baseUrl, apiKey, keyFile, model, policy, seconds,
                "", "", true, (AiConfigService) null);
    }

    public OpenAiCompatibleAssistantAdapter(ObjectMapper mapper, boolean enabled, String baseUrl,
            String apiKey, String keyFile, String model, String policy, long seconds,
            String provider, String trustedHttpOrigin, boolean toolCallsEnabled) {
        this(mapper, enabled, baseUrl, apiKey, keyFile, model, policy, seconds,
                provider, trustedHttpOrigin, toolCallsEnabled, (AiConfigService) null);
    }

    public OpenAiCompatibleAssistantAdapter(ObjectMapper mapper, boolean enabled, String baseUrl,
            String apiKey, String keyFile, String model, String policy, long seconds,
            String provider, String trustedHttpOrigin, boolean toolCallsEnabled,
            AiConfigService config) {
        this.mapper = mapper;
        this.enabled = enabled;
        this.baseUrl = baseUrl == null ? "" : baseUrl;
        this.apiKey = apiKey == null ? "" : apiKey;
        this.keyFile = keyFile == null ? "" : keyFile;
        this.model = model == null ? "" : model;
        this.policy = policy == null ? "disabled" : policy;
        this.provider = provider == null ? "" : provider;
        this.trustedHttpOrigin = trustedHttpOrigin == null ? "" : trustedHttpOrigin;
        this.toolCallsEnabled = toolCallsEnabled;
        this.timeout = Duration.ofSeconds(Math.max(5, Math.min(seconds, 60)));
        this.config = config;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }

    @Override
    public boolean supportsToolCalls() {
        return toolCallsEnabled;
    }

    private AiConfigService.RuntimeConfig runtime() {
        if (config != null) {
            try {
                AiConfigService.RuntimeConfig value = config.runtime();
                if (value != null) {
                    return value;
                }
            } catch (RuntimeException ignored) {
                // 数据库暂时不可用时保留环境变量兜底，不让助手整体不可用。
            }
        }
        return new AiConfigService.RuntimeConfig(enabled, baseUrl, model, readKey(),
                (int) timeout.toMillis(), 1800, policy, provider, "ENV");
    }

    private static String providerOf(AiConfigService.RuntimeConfig runtime) {
        if (runtime.provider() != null && !runtime.provider().isBlank()) {
            return runtime.provider();
        }
        return runtime.baseUrl() != null && runtime.baseUrl().startsWith("https://api.deepseek.com")
                ? "DeepSeek" : "实验室大模型";
    }

    private URI endpoint(String rawBaseUrl) {
        try {
            String endpoint = rawBaseUrl == null ? "" : rawBaseUrl.trim();
            endpoint = endpoint.replaceAll("/+$", "");
            if (!endpoint.endsWith("/chat/completions")) {
                endpoint += "/chat/completions";
            }
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getRawQuery() != null || uri.getFragment() != null) {
                throw new IllegalArgumentException();
            }
            if ("https".equalsIgnoreCase(uri.getScheme())) {
                return uri;
            }
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                if (List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())) {
                    return uri;
                }
                // Allow only the explicitly approved laboratory origin, never a subnet or arbitrary HTTP host.
                URI trusted = URI.create(trustedHttpOrigin.trim());
                if ("http".equalsIgnoreCase(trusted.getScheme()) && trusted.getHost() != null
                        && trusted.getUserInfo() == null && trusted.getRawQuery() == null
                        && trusted.getFragment() == null
                        && (trusted.getPath() == null || trusted.getPath().isEmpty() || "/".equals(trusted.getPath()))
                        && uri.getHost().equalsIgnoreCase(trusted.getHost())
                        && effectivePort(uri) == effectivePort(trusted)) {
                    return uri;
                }
            }
        } catch (Exception ignored) {
            /* Never expose URL/key details in an error. */
        }
        throw new ProviderException("INVALID_ENDPOINT",
                "模型地址无效；非HTTPS接口必须在后端配置精确的受信任实验室地址（含端口）");
    }

    private static int effectivePort(URI uri) {
        return uri.getPort() < 0
                ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort();
    }

    private String readKey() {
        if (!keyFile.isBlank()) {
            try {
                return Files.readString(Path.of(keyFile)).trim();
            } catch (Exception ignored) {
                return "";
            }
        }
        return apiKey.trim();
    }

    private ModelStatus status(AiConfigService.RuntimeConfig runtime) {
        boolean key = runtime.apiKey() != null && !runtime.apiKey().isBlank();
        boolean approved = "anonymized".equals(runtime.dataPolicy());
        boolean validEndpoint = true;
        try {
            endpoint(runtime.baseUrl());
        } catch (ProviderException ex) {
            validEndpoint = false;
        }
        boolean hasModel = runtime.model() != null && !runtime.model().isBlank();
        boolean ready = runtime.enabled() && key && approved && validEndpoint && hasModel;
        String provider = providerOf(runtime);
        String message = !runtime.enabled() ? provider + "尚未开启，当前可使用本地数据检索"
                : !approved ? "尚未批准脱敏数据外发，当前仅本地检索"
                : !key ? "请在「AI设备助手 → AI 服务配置」中填写模型 API Key"
                : !validEndpoint ? "模型地址未获准，请检查后端受信任地址配置"
                : !hasModel ? "请从模型列表中选择完整模型ID，不能只填写千问3等系列名"
                : "已配置" + provider + "；"
                        + (toolCallsEnabled ? "支持只读工具查询" : "由系统先检索数据后交给模型分析")
                        + "，尚不代表网络验证成功"
                        + ("DB".equals(runtime.source()) ? "（数据库配置）" : "（环境变量兜底）");
        return new ModelStatus(runtime.enabled(), key, ready, provider, runtime.model(),
                runtime.dataPolicy(), message);
    }

    @Override
    public ModelStatus status() {
        return status(runtime());
    }

    @Override
    public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        return complete(messages, tools, null);
    }

    /** 新版本 AssistantApplicationService 会传入剩余超时；保留旧两参方法兼容测试与旧调用。 */
    public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                               Duration remaining) {
        AiConfigService.RuntimeConfig runtime = runtime();
        ModelStatus current = status(runtime);
        if (!current.ready()) {
            throw new ProviderException("NOT_CONFIGURED", current.message());
        }
        try {
            URI uri = endpoint(runtime.baseUrl());
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", runtime.model());
            payload.put("messages", messages);
            payload.put("temperature", 0.2);
            payload.put("max_tokens", runtime.maxTokens());
            payload.put("stream", false);
            if ("api.deepseek.com".equals(uri.getHost())) {
                payload.put("thinking", Map.of("type", "disabled"));
            }
            if (toolCallsEnabled && tools != null && !tools.isEmpty()) {
                payload.put("tools", tools);
                payload.put("tool_choice", "auto");
            }
            Duration requestTimeout = remaining != null && !remaining.isNegative() && !remaining.isZero()
                    ? remaining : Duration.ofMillis(runtime.timeoutMs());
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + runtime.apiKey())
                    .header("Content-Type", "application/json")
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload)))
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code < 200 || code >= 300) {
                String reason = switch (code) {
                    case 401, 403 -> "API密钥无效或没有模型访问权限";
                    case 402 -> "模型账户余额或配额不足";
                    case 400, 404, 422 -> "模型ID或请求参数不被接口支持，请核对模型列表及工具调用配置";
                    case 429 -> "模型请求过于频繁，请稍后重试";
                    default -> "模型服务暂不可用（HTTP " + code + "）";
                };
                throw new ProviderException("PROVIDER_HTTP_" + code, reason);
            }
            JsonNode root = mapper.readTree(response.body());
            JsonNode msg = root.at("/choices/0/message");
            if (!msg.isObject()) {
                throw new ProviderException("INVALID_RESPONSE", "模型返回格式异常");
            }
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : msg.path("tool_calls")) {
                calls.add(new ToolCall(call.path("id").asText(), call.at("/function/name").asText(),
                        call.at("/function/arguments").asText("{}")));
            }
            // Exclude internal reasoning and all provider metadata.
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant");
            assistant.put("content", msg.path("content").asText(""));
            if (!calls.isEmpty()) {
                assistant.put("tool_calls", mapper.convertValue(msg.path("tool_calls"),
                        new TypeReference<List<Map<String, Object>>>() {
                        }));
            }
            return new Completion(msg.path("content").asText(""), calls, assistant,
                    root.at("/usage/prompt_tokens").asInt(), root.at("/usage/completion_tokens").asInt());
        } catch (ProviderException ex) {
            throw ex;
        } catch (HttpTimeoutException ex) {
            throw new ProviderException("TIMEOUT", "模型响应超时，请稍后重试");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ProviderException("INTERRUPTED", "请求已取消");
        } catch (Exception ex) {
            throw new ProviderException("CONNECTION_FAILED",
                    "无法连接模型服务，请检查服务器到模型服务的网络及API地址");
        }
    }
}
