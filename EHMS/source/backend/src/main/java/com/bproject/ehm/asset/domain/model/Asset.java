package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.Locale;

public record Asset(
        String code,
        String name,
        String type,
        String area,
        String maintenanceDate,
        String owner,
        boolean archived,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public static Asset create(String code, String name, String type, String area,
                               String maintenanceDate, String owner, Instant now) {
        return new Asset(normalizeCode(code), required(name, "设备名称"), fallback(type, "未分类"),
                fallback(area, "未分区"), maintenanceDate, fallback(owner, "待分配"),
                false, now, now, null);
    }

    public Asset update(String name, String type, String area, String maintenanceDate, String owner, Instant now) {
        if (archived) throw new DomainConflictException("已归档设备不能修改");
        return new Asset(code, required(name, "设备名称"), fallback(type, "未分类"),
                fallback(area, "未分区"), maintenanceDate, fallback(owner, "待分配"),
                false, createdAt, now, version);
    }

    public Asset archive(Instant now) {
        if (archived) return this;
        return new Asset(code, name, type, area, maintenanceDate, owner, true, createdAt, now, version);
    }

    public static String normalizeCode(String code) {
        return required(code, "设备编码").toUpperCase(Locale.ROOT);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }
}
