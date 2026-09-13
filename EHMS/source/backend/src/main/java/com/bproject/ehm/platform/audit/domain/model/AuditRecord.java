package com.bproject.ehm.platform.audit.domain.model;

import java.time.Instant;

public record AuditRecord(
        String auditId, Instant occurredAt, String operator, String role,
        String method, String path, int statusCode, String outcome,
        String traceId, String clientAddress, long durationMs
) {
    public static AuditRecord create(String auditId, Instant occurredAt, String operator, String role,
                                     String method, String path, int statusCode, String traceId,
                                     String clientAddress, long durationMs) {
        return new AuditRecord(required(auditId, "审计编号"), occurredAt == null ? Instant.now() : occurredAt,
                fallback(operator, "未识别用户"), fallback(role, "未识别角色"), required(method, "请求方法"),
                required(path, "请求路径"), statusCode, statusCode < 400 ? "SUCCESS" : "FAILED",
                fallback(traceId, "无追踪编号"), fallback(clientAddress, "未知地址"), Math.max(0, durationMs));
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
