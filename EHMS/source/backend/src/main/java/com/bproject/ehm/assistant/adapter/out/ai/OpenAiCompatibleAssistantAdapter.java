package com.bproject.ehm.assistant.adapter.out.ai;

import com.bproject.ehm.assistant.ports.AssistantModelPort;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
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
            @Value("${ehm.ai.tools-enabled:true}") boolean toolCallsEnabled) {
        this.mapper = mapper; this.enabled = enabled; this.baseUrl = baseUrl;
        this.apiKey = apiKey; this.keyFile = keyFile; this.model = model; this.policy = policy;
        this.provider = provider.isBlank() ? (baseUrl.startsWith("https://api.deepseek.com") ? "DeepSeek" : "实验室大模型") : provider;
        this.trustedHttpOrigin = trustedHttpOrigin;
        this.toolCallsEnabled = toolCallsEnabled;
        this.timeout = Duration.ofSeconds(Math.max(5, Math.min(seconds, 60)));
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    }
    // Retained for existing transports/tests; HTTP origins are denied by default.
    public OpenAiCompatibleAssistantAdapter(ObjectMapper mapper, boolean enabled, String baseUrl,
            String apiKey, String keyFile, String model, String policy, long seconds) {
        this(mapper, enabled, baseUrl, apiKey, keyFile, model, policy, seconds, "", "", true);
    }
    @Override public boolean supportsToolCalls() { return toolCallsEnabled; }
    private URI endpoint() {
        try {
            String endpoint = baseUrl.trim().replaceAll("/+$", "");
            if (!endpoint.endsWith("/chat/completions")) endpoint += "/chat/completions";
            URI uri = URI.create(endpoint);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getRawQuery() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
            if ("http".equalsIgnoreCase(uri.getScheme())) {
                if (List.of("localhost", "127.0.0.1", "[::1]", "::1").contains(uri.getHost())) return uri;
                // Allow only the explicitly approved laboratory origin, never a subnet or arbitrary HTTP host.
                URI trusted = URI.create(trustedHttpOrigin.trim());
                if ("http".equalsIgnoreCase(trusted.getScheme()) && trusted.getHost() != null
                        && trusted.getUserInfo() == null && trusted.getRawQuery() == null && trusted.getFragment() == null
                        && (trusted.getPath() == null || trusted.getPath().isEmpty() || "/".equals(trusted.getPath()))
                        && uri.getHost().equalsIgnoreCase(trusted.getHost())
                        && effectivePort(uri) == effectivePort(trusted)) return uri;
            }
        } catch (Exception ignored) { /* Never expose URL/key details in an error. */ }
        throw new ProviderException("INVALID_ENDPOINT", "模型地址无效；非HTTPS接口必须在后端配置精确的受信任实验室地址（含端口）");
    }
    private static int effectivePort(URI uri) { return uri.getPort() < 0 ? ("https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80) : uri.getPort(); }
    private String readKey() {
        if (!keyFile.isBlank()) {
            try { return Files.readString(Path.of(keyFile)).trim(); }
            catch (Exception ignored) { return ""; }
        }
        return apiKey.trim();
    }
    @Override public ModelStatus status() {
        boolean key = !readKey().isBlank();
        boolean approved = "anonymized".equals(policy);
        boolean validEndpoint = true;
        try { endpoint(); } catch (ProviderException ex) { validEndpoint = false; }
        boolean ready = enabled && key && approved && validEndpoint && !model.isBlank();
        String message = !enabled ? provider + "尚未开启，当前可使用本地数据检索"
                : !approved ? "尚未批准脱敏数据外发，当前仅本地检索"
                : !key ? "请在服务器密钥文件中填写模型 API Key"
                : !validEndpoint ? "模型地址未获准，请检查后端受信任地址配置"
                : model.isBlank() ? "请从模型列表中选择完整模型ID，不能只填写千问3等系列名"
                : "已配置" + provider + "；" + (toolCallsEnabled ? "支持只读工具查询" : "由系统先检索数据后交给模型分析") + "，尚不代表网络验证成功";
        return new ModelStatus(enabled, key, ready, provider, model, policy, message);
    }
    @Override public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools) {
        if (!status().ready()) throw new ProviderException("NOT_CONFIGURED", status().message());
        try {
            URI uri = endpoint();
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("model", model); payload.put("messages", messages);
            payload.put("temperature", 0.2); payload.put("max_tokens", 1800); payload.put("stream", false);
            if ("api.deepseek.com".equals(uri.getHost())) payload.put("thinking", Map.of("type", "disabled"));
            if (toolCallsEnabled && !tools.isEmpty()) { payload.put("tools", tools); payload.put("tool_choice", "auto"); }
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .header("Authorization", "Bearer " + readKey()).header("Content-Type", "application/json")
                    .timeout(timeout).POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(payload))).build();
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
            if (!msg.isObject()) throw new ProviderException("INVALID_RESPONSE", "模型返回格式异常");
            List<ToolCall> calls = new ArrayList<>();
            for (JsonNode call : msg.path("tool_calls")) {
                calls.add(new ToolCall(call.path("id").asText(), call.at("/function/name").asText(),
                        call.at("/function/arguments").asText("{}")));
            }
            // Exclude internal reasoning and all provider metadata.
            Map<String, Object> assistant = new LinkedHashMap<>();
            assistant.put("role", "assistant"); assistant.put("content", msg.path("content").asText(""));
            if (!calls.isEmpty()) assistant.put("tool_calls", mapper.convertValue(msg.path("tool_calls"), new TypeReference<List<Map<String, Object>>>() {}));
            return new Completion(msg.path("content").asText(""), calls, assistant,
                    root.at("/usage/prompt_tokens").asInt(), root.at("/usage/completion_tokens").asInt());
        } catch (ProviderException ex) { throw ex; }
        catch (HttpTimeoutException ex) { throw new ProviderException("TIMEOUT", "模型响应超时，请稍后重试"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new ProviderException("INTERRUPTED", "请求已取消"); }
        catch (Exception ex) { throw new ProviderException("CONNECTION_FAILED", "无法连接模型服务，请检查服务器到模型服务的网络及API地址"); }
    }
}
