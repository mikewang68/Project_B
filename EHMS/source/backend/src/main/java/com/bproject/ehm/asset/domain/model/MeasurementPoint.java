package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.Locale;

public record MeasurementPoint(
        String code,
        String assetCode,
        String componentCode,
        String name,
        String metric,
        String unit,
        String sourceProtocol,
        String sourceAddress,
        int sampleIntervalSeconds,
        Double lowerLimit,
        Double upperLimit,
        boolean enabled,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static MeasurementPoint create(String code, String assetCode, String componentCode, String name,
                                          String metric, String unit, String sourceProtocol, String sourceAddress,
                                          Integer sampleIntervalSeconds, Double lowerLimit, Double upperLimit,
                                          Boolean enabled, Instant now) {
        int interval = validateInterval(sampleIntervalSeconds == null ? 5 : sampleIntervalSeconds);
        validateLimits(lowerLimit, upperLimit);
        return new MeasurementPoint(normalizeCode(code), Asset.normalizeCode(assetCode),
                Component.normalizeCode(componentCode), required(name, "测点名称"), fallback(metric, "通用测量"),
                fallback(unit, "—"), fallback(sourceProtocol, "待确认"), fallback(sourceAddress, "待确认"),
                interval, lowerLimit, upperLimit, enabled == null || enabled, false, now, now, null);
    }

    public MeasurementPoint update(String componentCode, String name, String metric, String unit,
                                   String sourceProtocol, String sourceAddress, Integer sampleIntervalSeconds,
                                   Double lowerLimit, Double upperLimit, Boolean enabled, Instant now) {
        if (archived) throw new DomainConflictException("已归档测点不能修改");
        int interval = validateInterval(sampleIntervalSeconds == null ? this.sampleIntervalSeconds : sampleIntervalSeconds);
        validateLimits(lowerLimit, upperLimit);
        return new MeasurementPoint(code, assetCode, Component.normalizeCode(componentCode),
                required(name, "测点名称"), fallback(metric, "通用测量"), fallback(unit, "—"),
                fallback(sourceProtocol, "待确认"), fallback(sourceAddress, "待确认"), interval,
                lowerLimit, upperLimit, enabled == null ? this.enabled : enabled, false, createdAt, now, version);
    }

    public MeasurementPoint archive(Instant now) {
        if (archived) return this;
        return new MeasurementPoint(code, assetCode, componentCode, name, metric, unit, sourceProtocol,
                sourceAddress, sampleIntervalSeconds, lowerLimit, upperLimit, false, true, createdAt, now, version);
    }

    public static String normalizeCode(String value) {
        return required(value, "测点编码").toUpperCase(Locale.ROOT);
    }

    private static int validateInterval(int value) {
        if (value < 1 || value > 86400) throw new IllegalArgumentException("采样周期必须在1至86400秒之间");
        return value;
    }

    private static void validateLimits(Double lower, Double upper) {
        if (lower != null && upper != null && lower >= upper) throw new IllegalArgumentException("量程下限必须小于上限");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
