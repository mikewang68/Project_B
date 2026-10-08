package com.bproject.ehm.assistant.ports;

import java.util.List;
import java.util.Map;

public interface AssistantModelPort {
    ModelStatus status();
    default boolean supportsToolCalls() { return true; }
    Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools);

    record ModelStatus(boolean enabled, boolean keyConfigured, boolean ready, String provider,
                       String model, String dataPolicy, String message) {}
    record ToolCall(String id, String name, String arguments) {}
    record Completion(String content, List<ToolCall> toolCalls, Map<String, Object> assistantMessage,
                      int promptTokens, int completionTokens) {}
    class ProviderException extends RuntimeException {
        private final String code;
        public ProviderException(String code, String message) { super(message); this.code = code; }
        public String code() { return code; }
    }
}
