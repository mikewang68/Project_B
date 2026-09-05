package com.bproject.safety.common.error;

import java.time.OffsetDateTime;
import java.util.Map;

/**
 * 统一错误响应体。
 *
 * @param code      稳定字符串错误码，如 STATE_CONFLICT
 * @param message   面向用户的错误摘要（不包含 Java 堆栈）
 * @param traceId   与响应头 X-Trace-Id 一致
 * @param details   可选的字段级细节（如参数校验错误）
 * @param timestamp ISO-8601 带时区时间
 */
public record ApiError(
        String code,
        String message,
        String traceId,
        Map<String, Object> details,
        OffsetDateTime timestamp) {

    public static ApiError of(String code, String message, String traceId, Map<String, Object> details) {
        return new ApiError(code, message, traceId, details, OffsetDateTime.now());
    }
}
