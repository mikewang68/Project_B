package com.bproject.ehm.reliability.domain.model;

import java.time.Instant;

public record FailureMode(
        String failureModeId, String faultCode, String assetType, String component,
        String failureName, String failureEffect, String failureCause,
        int severity, int occurrence, int detectability, int rpn,
        String currentControl, String recommendedAction, String owner,
        String status, Instant createdAt, Instant updatedAt, Long version
) {
    public static FailureMode create(String id, String faultCode, String assetType, String component,
                                     String name, String effect, String cause, int severity,
                                     int occurrence, int detectability, String control,
                                     String action, String owner, Instant now) {
        validateScore(severity, "严重度");
        validateScore(occurrence, "发生度");
        validateScore(detectability, "探测度");
        return new FailureMode(required(id, "失效模式编号"), required(faultCode, "故障编码").toUpperCase(),
                required(assetType, "设备类型"), required(component, "部件"), required(name, "失效模式"),
                required(effect, "失效影响"), required(cause, "失效原因"), severity, occurrence,
                detectability, severity * occurrence * detectability, fallback(control, "待补充"),
                fallback(action, "待制定"), fallback(owner, "设备工程师"), "ACTIVE", now, now, null);
    }

    public FailureMode revise(String assetType, String component, String name, String effect,
                              String cause, int severity, int occurrence, int detectability,
                              String control, String action, String owner, Instant now) {
        FailureMode revised = create(failureModeId, faultCode, assetType, component, name, effect,
                cause, severity, occurrence, detectability, control, action, owner, now);
        return new FailureMode(revised.failureModeId, revised.faultCode, revised.assetType,
                revised.component, revised.failureName, revised.failureEffect, revised.failureCause,
                revised.severity, revised.occurrence, revised.detectability, revised.rpn,
                revised.currentControl, revised.recommendedAction, revised.owner, status,
                createdAt, now, version);
    }

    private static void validateScore(int value, String label) {
        if (value < 1 || value > 10) throw new IllegalArgumentException(label + "必须为1～10");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
