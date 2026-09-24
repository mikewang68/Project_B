package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

public record ConfigurationChange(
        String changeNo,
        String assetCode,
        String objectType,
        String objectCode,
        String changeType,
        Map<String, Object> beforeSnapshot,
        Map<String, Object> afterSnapshot,
        String reason,
        String impactAssessment,
        String applicant,
        String approver,
        String executor,
        String status,
        String rollbackReference,
        Instant requestedAt,
        Instant approvedAt,
        Instant effectiveAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public ConfigurationChange {
        beforeSnapshot = immutable(beforeSnapshot);
        afterSnapshot = immutable(afterSnapshot);
    }

    public static ConfigurationChange create(String no, String assetCode, String objectType,
                                             String objectCode, String changeType,
                                             Map<String, Object> beforeSnapshot,
                                             Map<String, Object> afterSnapshot, String reason,
                                             String impactAssessment, String applicant,
                                             String rollbackReference, Instant now) {
        if (afterSnapshot == null || afterSnapshot.isEmpty()) throw new IllegalArgumentException("变更后配置快照不能为空");
        return new ConfigurationChange(normalize(no), normalize(assetCode),
                fallback(objectType, "DEVICE"), required(objectCode, "变更对象编码"),
                fallback(changeType, "参数变更"), beforeSnapshot, afterSnapshot,
                required(reason, "变更原因"), fallback(impactAssessment, "待审批时复核影响"),
                fallback(applicant, "设备管理员"), null, null, "DRAFT", trim(rollbackReference),
                now, null, null, now, now, null);
    }

    public ConfigurationChange approve(String approver, Instant now) {
        if (!"DRAFT".equals(status)) throw new DomainConflictException("只有草稿变更可以审批");
        return new ConfigurationChange(changeNo, assetCode, objectType, objectCode, changeType,
                beforeSnapshot, afterSnapshot, reason, impactAssessment, applicant,
                fallback(approver, "设备主管"), executor, "APPROVED", rollbackReference,
                requestedAt, now, null, createdAt, now, version);
    }

    public ConfigurationChange apply(String executor, Instant now) {
        if (!"APPROVED".equals(status)) throw new DomainConflictException("配置变更必须先审批再生效");
        return new ConfigurationChange(changeNo, assetCode, objectType, objectCode, changeType,
                beforeSnapshot, afterSnapshot, reason, impactAssessment, applicant, approver,
                fallback(executor, "设备管理员"), "EFFECTIVE", rollbackReference,
                requestedAt, approvedAt, now, createdAt, now, version);
    }

    private static Map<String, Object> immutable(Map<String, Object> source) {
        return source == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(source));
    }
    private static String normalize(String value) { return required(value, "编码").toUpperCase(Locale.ROOT); }
    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }
    private static String fallback(String value, String replacement) { return value == null || value.isBlank() ? replacement : value.trim(); }
    private static String trim(String value) { return value == null || value.isBlank() ? null : value.trim(); }
}
