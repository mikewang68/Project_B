package com.bproject.ehm.monitoring.domain.model;

import java.time.Instant;

public record DeviceSnapshot(
        String deviceCode,
        String condition,
        Integer health,
        String risk,
        String riskClass,
        Double quality,
        String ready,
        String alarm,
        Double temperature,
        Double vibration,
        Double current,
        Integer rulDays,
        Instant updatedAt,
        Long version
) {
    public static DeviceSnapshot initial(String deviceCode, String condition, Integer health, String risk,
                                         String riskClass, Double quality, String ready, String alarm,
                                         Double temperature, Double vibration, Double current, Integer rulDays,
                                         Instant now) {
        return new DeviceSnapshot(deviceCode, fallback(condition, "待接入"), health,
                fallback(risk, "待评估"), fallback(riskClass, "limited"), quality,
                fallback(ready, "待接入"), fallback(alarm, "无活动告警"), temperature,
                vibration, current, rulDays, now, null);
    }

    public DeviceSnapshot update(String condition, Integer health, String risk, String riskClass,
                                 Double quality, String ready, String alarm, Double temperature,
                                 Double vibration, Double current, Integer rulDays, Instant now) {
        return new DeviceSnapshot(deviceCode, fallback(condition, "待接入"), health,
                fallback(risk, "待评估"), fallback(riskClass, "limited"), quality,
                fallback(ready, "待接入"), fallback(alarm, "无活动告警"), temperature,
                vibration, current, rulDays, now, version);
    }

    public DeviceSnapshot archived(Instant now) {
        return new DeviceSnapshot(deviceCode, "已归档", health, "已停用", "offline", quality,
                "停用", alarm, temperature, vibration, current, rulDays, now, version);
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
