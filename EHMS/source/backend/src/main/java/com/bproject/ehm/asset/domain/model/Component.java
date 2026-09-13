package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.Locale;

public record Component(
        String code,
        String assetCode,
        String parentCode,
        String name,
        String category,
        String manufacturer,
        String model,
        String serialNumber,
        String criticality,
        String position,
        String installedOn,
        String status,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static Component create(String code, String assetCode, String parentCode, String name, String category,
                                   String manufacturer, String model, String serialNumber, String criticality,
                                   String position, String installedOn, String status, Instant now) {
        return new Component(normalizeCode(code), Asset.normalizeCode(assetCode), normalizeOptional(parentCode),
                required(name, "部件名称"), fallback(category, "未分类"), fallback(manufacturer, "待确认"),
                fallback(model, "待确认"), fallback(serialNumber, "待确认"), fallback(criticality, "B类"),
                fallback(position, "待确认"), installedOn, fallback(status, "在役"), false, now, now, null);
    }

    public Component update(String parentCode, String name, String category, String manufacturer, String model,
                            String serialNumber, String criticality, String position, String installedOn,
                            String status, Instant now) {
        if (archived) throw new DomainConflictException("已归档部件不能修改");
        return new Component(code, assetCode, normalizeOptional(parentCode), required(name, "部件名称"),
                fallback(category, "未分类"), fallback(manufacturer, "待确认"), fallback(model, "待确认"),
                fallback(serialNumber, "待确认"), fallback(criticality, "B类"), fallback(position, "待确认"),
                installedOn, fallback(status, "在役"), false, createdAt, now, version);
    }

    public Component archive(Instant now) {
        if (archived) return this;
        return new Component(code, assetCode, parentCode, name, category, manufacturer, model, serialNumber,
                criticality, position, installedOn, "已归档", true, createdAt, now, version);
    }

    public static String normalizeCode(String value) {
        return required(value, "部件编码").toUpperCase(Locale.ROOT);
    }

    private static String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
