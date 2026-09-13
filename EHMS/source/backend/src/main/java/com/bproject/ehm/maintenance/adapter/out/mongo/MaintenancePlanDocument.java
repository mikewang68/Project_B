package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.InspectionTemplateItem;
import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("maintenance_plans")
public class MaintenancePlanDocument {
    @Id private final String planId;
    @Indexed private final String assetCode;
    private final String assetName;
    private final String componentCode;
    private final String name;
    private final String strategyType;
    private final int cycleDays;
    private final String triggerCondition;
    private final List<InspectionTemplateItem> checklistTemplate;
    private final String ownerTeam;
    @Indexed private final Instant nextDueAt;
    private final boolean enabled;
    private final Instant lastGeneratedAt;
    private final Instant createdAt;
    private final Instant updatedAt;
    @Version private final Long version;

    public MaintenancePlanDocument(String planId, String assetCode, String assetName,
                                   String componentCode, String name, String strategyType,
                                   int cycleDays, String triggerCondition,
                                   List<InspectionTemplateItem> checklistTemplate, String ownerTeam,
                                   Instant nextDueAt, boolean enabled, Instant lastGeneratedAt,
                                   Instant createdAt, Instant updatedAt, Long version) {
        this.planId = planId;
        this.assetCode = assetCode;
        this.assetName = assetName;
        this.componentCode = componentCode;
        this.name = name;
        this.strategyType = strategyType;
        this.cycleDays = cycleDays;
        this.triggerCondition = triggerCondition;
        this.checklistTemplate = checklistTemplate;
        this.ownerTeam = ownerTeam;
        this.nextDueAt = nextDueAt;
        this.enabled = enabled;
        this.lastGeneratedAt = lastGeneratedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
    }

    static MaintenancePlanDocument fromDomain(MaintenancePlan value) {
        return new MaintenancePlanDocument(value.planId(), value.assetCode(), value.assetName(),
                value.componentCode(), value.name(), value.strategyType(), value.cycleDays(),
                value.triggerCondition(), value.checklistTemplate(), value.ownerTeam(), value.nextDueAt(),
                value.enabled(), value.lastGeneratedAt(), value.createdAt(), value.updatedAt(), value.version());
    }

    MaintenancePlan toDomain() {
        return new MaintenancePlan(planId, assetCode, assetName, componentCode, name, strategyType,
                cycleDays, triggerCondition, checklistTemplate, ownerTeam, nextDueAt, enabled,
                lastGeneratedAt, createdAt, updatedAt, version);
    }
}
