package com.bproject.ehm.reliability.adapter.out.mongo;

import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("reliability_failure_modes")
class FailureModeDocument {
    @Id final String failureModeId;
    final String faultCode;
    final String assetType;
    final String component;
    final String failureName;
    final String failureEffect;
    final String failureCause;
    final int severity;
    final int occurrence;
    final int detectability;
    final int rpn;
    final String currentControl;
    final String recommendedAction;
    final String owner;
    final String status;
    final Instant createdAt;
    final Instant updatedAt;
    @Version final Long version;

    FailureModeDocument(String failureModeId, String faultCode, String assetType, String component,
                        String failureName, String failureEffect, String failureCause, int severity,
                        int occurrence, int detectability, int rpn, String currentControl,
                        String recommendedAction, String owner, String status, Instant createdAt,
                        Instant updatedAt, Long version) {
        this.failureModeId = failureModeId; this.faultCode = faultCode; this.assetType = assetType;
        this.component = component; this.failureName = failureName; this.failureEffect = failureEffect;
        this.failureCause = failureCause; this.severity = severity; this.occurrence = occurrence;
        this.detectability = detectability; this.rpn = rpn; this.currentControl = currentControl;
        this.recommendedAction = recommendedAction; this.owner = owner; this.status = status;
        this.createdAt = createdAt; this.updatedAt = updatedAt; this.version = version;
    }

    static FailureModeDocument from(FailureMode v) {
        return new FailureModeDocument(v.failureModeId(), v.faultCode(), v.assetType(), v.component(),
                v.failureName(), v.failureEffect(), v.failureCause(), v.severity(), v.occurrence(),
                v.detectability(), v.rpn(), v.currentControl(), v.recommendedAction(), v.owner(),
                v.status(), v.createdAt(), v.updatedAt(), v.version());
    }

    FailureMode toDomain() {
        return new FailureMode(failureModeId, faultCode, assetType, component, failureName,
                failureEffect, failureCause, severity, occurrence, detectability, rpn,
                currentControl, recommendedAction, owner, status, createdAt, updatedAt, version);
    }
}

@Document("reliability_alarm_rules")
class AlarmRuleDocument {
    @Id final String ruleCode;
    final String name;
    final String assetType;
    final String metric;
    final String conditionExpression;
    final String recoveryExpression;
    final String severity;
    final int persistenceSeconds;
    final String versionLabel;
    final String status;
    final long hitCount;
    final long falsePositiveCount;
    final String owner;
    final Instant createdAt;
    final Instant updatedAt;
    @Version final Long version;

    AlarmRuleDocument(String ruleCode, String name, String assetType, String metric,
                      String conditionExpression, String recoveryExpression, String severity,
                      int persistenceSeconds, String versionLabel, String status, long hitCount,
                      long falsePositiveCount, String owner, Instant createdAt, Instant updatedAt,
                      Long version) {
        this.ruleCode = ruleCode; this.name = name; this.assetType = assetType; this.metric = metric;
        this.conditionExpression = conditionExpression; this.recoveryExpression = recoveryExpression;
        this.severity = severity; this.persistenceSeconds = persistenceSeconds;
        this.versionLabel = versionLabel; this.status = status; this.hitCount = hitCount;
        this.falsePositiveCount = falsePositiveCount; this.owner = owner; this.createdAt = createdAt;
        this.updatedAt = updatedAt; this.version = version;
    }

    static AlarmRuleDocument from(AlarmRule v) {
        return new AlarmRuleDocument(v.ruleCode(), v.name(), v.assetType(), v.metric(),
                v.conditionExpression(), v.recoveryExpression(), v.severity(), v.persistenceSeconds(),
                v.versionLabel(), v.status(), v.hitCount(), v.falsePositiveCount(), v.owner(),
                v.createdAt(), v.updatedAt(), v.version());
    }

    AlarmRule toDomain() {
        return new AlarmRule(ruleCode, name, assetType, metric, conditionExpression,
                recoveryExpression, severity, persistenceSeconds, versionLabel, status,
                hitCount, falsePositiveCount, owner, createdAt, updatedAt, version);
    }
}

@Document("reliability_knowledge_cases")
class KnowledgeCaseDocument {
    @Id final String caseNo;
    final String faultCode;
    final String assetType;
    final String component;
    final String title;
    final String symptom;
    final String confirmedCause;
    final List<String> diagnosisSteps;
    final String remedy;
    final String verificationCriterion;
    final String sourceWorkOrderNo;
    final String status;
    final String verifiedBy;
    final Instant verifiedAt;
    final Instant createdAt;
    final Instant updatedAt;
    @Version final Long version;

    KnowledgeCaseDocument(String caseNo, String faultCode, String assetType, String component,
                          String title, String symptom, String confirmedCause, List<String> diagnosisSteps,
                          String remedy, String verificationCriterion, String sourceWorkOrderNo,
                          String status, String verifiedBy, Instant verifiedAt, Instant createdAt,
                          Instant updatedAt, Long version) {
        this.caseNo = caseNo; this.faultCode = faultCode; this.assetType = assetType;
        this.component = component; this.title = title; this.symptom = symptom;
        this.confirmedCause = confirmedCause; this.diagnosisSteps = diagnosisSteps;
        this.remedy = remedy; this.verificationCriterion = verificationCriterion;
        this.sourceWorkOrderNo = sourceWorkOrderNo; this.status = status; this.verifiedBy = verifiedBy;
        this.verifiedAt = verifiedAt; this.createdAt = createdAt; this.updatedAt = updatedAt;
        this.version = version;
    }

    static KnowledgeCaseDocument from(KnowledgeCase v) {
        return new KnowledgeCaseDocument(v.caseNo(), v.faultCode(), v.assetType(), v.component(),
                v.title(), v.symptom(), v.confirmedCause(), v.diagnosisSteps(), v.remedy(),
                v.verificationCriterion(), v.sourceWorkOrderNo(), v.status(), v.verifiedBy(),
                v.verifiedAt(), v.createdAt(), v.updatedAt(), v.version());
    }

    KnowledgeCase toDomain() {
        return new KnowledgeCase(caseNo, faultCode, assetType, component, title, symptom,
                confirmedCause, diagnosisSteps, remedy, verificationCriterion, sourceWorkOrderNo,
                status, verifiedBy, verifiedAt, createdAt, updatedAt, version);
    }
}

@Document("reliability_sla_policies")
class SlaPolicyDocument {
    @Id final String severity;
    final int acknowledgeMinutes;
    final int assignMinutes;
    final int recoverMinutes;
    final String escalationRole;
    final boolean enabled;
    final Instant updatedAt;
    @Version final Long version;

    SlaPolicyDocument(String severity, int acknowledgeMinutes, int assignMinutes, int recoverMinutes,
                      String escalationRole, boolean enabled, Instant updatedAt, Long version) {
        this.severity = severity; this.acknowledgeMinutes = acknowledgeMinutes;
        this.assignMinutes = assignMinutes; this.recoverMinutes = recoverMinutes;
        this.escalationRole = escalationRole; this.enabled = enabled;
        this.updatedAt = updatedAt; this.version = version;
    }

    static SlaPolicyDocument from(SlaPolicy v) {
        return new SlaPolicyDocument(v.severity(), v.acknowledgeMinutes(), v.assignMinutes(),
                v.recoverMinutes(), v.escalationRole(), v.enabled(), v.updatedAt(), v.version());
    }

    SlaPolicy toDomain() {
        return new SlaPolicy(severity, acknowledgeMinutes, assignMinutes, recoverMinutes,
                escalationRole, enabled, updatedAt, version);
    }
}
