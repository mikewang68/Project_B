package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

public record MaintenancePlan(
        String planId,
        String assetCode,
        String assetName,
        String componentCode,
        String name,
        String strategyType,
        int cycleDays,
        String triggerCondition,
        List<InspectionTemplateItem> checklistTemplate,
        String ownerTeam,
        Instant nextDueAt,
        boolean enabled,
        Instant lastGeneratedAt,
        Instant createdAt,
        Instant updatedAt,
        Long version
) {
    public MaintenancePlan {
        checklistTemplate = checklistTemplate == null ? List.of() : List.copyOf(checklistTemplate);
    }

    public static MaintenancePlan create(String planId, String assetCode, String assetName,
                                         String componentCode, String name, String strategyType,
                                         int cycleDays, String triggerCondition,
                                         List<InspectionTemplateItem> checklistTemplate,
                                         String ownerTeam, Instant nextDueAt, Instant now) {
        if (cycleDays < 1 || cycleDays > 3650) throw new IllegalArgumentException("维护周期必须为1～3650天");
        if (checklistTemplate == null || checklistTemplate.isEmpty()) {
            throw new IllegalArgumentException("至少需要一个点检项");
        }
        return new MaintenancePlan(required(planId, "计划编号"), required(assetCode, "设备编码"),
                required(assetName, "设备名称"), fallback(componentCode, "设备本体"),
                required(name, "计划名称"), fallback(strategyType, "PERIODIC"), cycleDays,
                fallback(triggerCondition, "按周期触发"), checklistTemplate,
                fallback(ownerTeam, "待分配"), nextDueAt == null ? now.plus(cycleDays, ChronoUnit.DAYS) : nextDueAt,
                true, null, now, now, null);
    }

    public MaintenancePlan markGenerated(Instant generatedAt) {
        if (!enabled) throw new DomainConflictException("已停用计划不能生成点检任务");
        return new MaintenancePlan(planId, assetCode, assetName, componentCode, name, strategyType,
                cycleDays, triggerCondition, checklistTemplate, ownerTeam,
                generatedAt.plus(cycleDays, ChronoUnit.DAYS), true, generatedAt,
                createdAt, generatedAt, version);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
