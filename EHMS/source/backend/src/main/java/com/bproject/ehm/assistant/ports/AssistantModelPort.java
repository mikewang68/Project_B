package com.bproject.ehm.assistant.ports;

import java.util.List;
import java.util.Map;

public interface AssistantModelPort {
    ModelStatus status();
    /** Only server-registered identifiers are accepted; clients never supply endpoints or keys. */
    default AssistantModelPort select(String id) {
        if (id == null || id.isBlank() || id.equals("default")) return this;
        throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "未知模型，请从模型列表选择");
    }
    default List<ModelOption> catalog() { return List.of(new ModelOption("default", status().provider(), status())); }
    default String defaultModelId() { return "default"; }
    default boolean supportsToolCalls() { return true; }
    Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools);
    /** Remaining end-to-end budget, not an independent timeout for every tool round. */
    default Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                java.time.Duration remaining) {
        if (remaining.isZero() || remaining.isNegative()) throw new ProviderException("TIMEOUT", "本轮分析已达到等待时限");
        return complete(messages, tools);
    }

    record ModelStatus(boolean enabled, boolean keyConfigured, boolean ready, String provider,
                       String model, String dataPolicy, String message) {}
    record ModelOption(String id, String label, ModelStatus status) {}
    record ToolCall(String id, String name, String arguments) {}
    record Completion(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                      int promptTokens, int completionTokens) {}
    class ProviderException extends RuntimeException {
        private final String code;
        public ProviderException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
