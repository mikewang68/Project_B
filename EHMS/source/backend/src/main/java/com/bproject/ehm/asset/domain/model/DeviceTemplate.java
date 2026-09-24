package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;

public record DeviceTemplate(
        String templateCode,
        String name,
        String deviceType,
        int revision,
        String status,
        List<TemplateComponent> components,
        List<TemplatePoint> measurementPoints,
        String inspectionPolicy,
        String criticality,
        String createdBy,
        String approvedBy,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public DeviceTemplate {
        components = components == null ? List.of() : List.copyOf(components);
        measurementPoints = measurementPoints == null ? List.of() : List.copyOf(measurementPoints);
    }

    public static DeviceTemplate create(String code, String name, String deviceType,
                                        List<TemplateComponent> components, List<TemplatePoint> points,
                                        String inspectionPolicy, String criticality,
                                        String createdBy, Instant now) {
        if ((components == null || components.isEmpty()) && (points == null || points.isEmpty())) {
            throw new IllegalArgumentException("设备模板至少需要一个部件或测点定义");
        }
        return new DeviceTemplate(normalize(code), required(name, "模板名称"), required(deviceType, "设备类型"),
                1, "DRAFT", components, points, fallback(inspectionPolicy, "按设备类型配置"),
                fallback(criticality, "一般"), fallback(createdBy, "设备管理员"),
                null, null, now, now, null);
    }

    public DeviceTemplate revise(String name, String deviceType, List<TemplateComponent> components,
                                 List<TemplatePoint> points, String inspectionPolicy,
                                 String criticality, Instant now) {
        if (!"DRAFT".equals(status)) throw new DomainConflictException("已发布模板不可直接修改，请创建新修订版");
        if ((components == null || components.isEmpty()) && (points == null || points.isEmpty())) {
            throw new IllegalArgumentException("设备模板至少需要一个部件或测点定义");
        }
        return new DeviceTemplate(templateCode, required(name, "模板名称"), required(deviceType, "设备类型"),
                revision, status, components, points, fallback(inspectionPolicy, this.inspectionPolicy),
                fallback(criticality, this.criticality), createdBy, approvedBy, publishedAt,
                createdAt, now, version);
    }

    public DeviceTemplate publish(String approver, Instant now) {
        if ("PUBLISHED".equals(status)) return this;
        if (!"DRAFT".equals(status)) throw new DomainConflictException("当前模板状态不允许发布");
        return new DeviceTemplate(templateCode, name, deviceType, revision, "PUBLISHED", components,
                measurementPoints, inspectionPolicy, criticality, createdBy,
                fallback(approver, "设备主管"), now, createdAt, now, version);
    }

    public record TemplateComponent(String componentCode, String name, String category, int quantity, boolean critical) {
        public TemplateComponent { if (quantity < 1) quantity = 1; }
    }
    public record TemplatePoint(String pointCode, String name, String metric, String unit,
                                double sampleRateHz, Double minValue, Double maxValue, boolean critical) {
    }

    private static String normalize(String value) { return required(value, "模板编码").toUpperCase(Locale.ROOT); }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
    private static String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
}
