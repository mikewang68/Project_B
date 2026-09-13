package com.bproject.ehm.reliability.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.List;

public record KnowledgeCase(
        String caseNo, String faultCode, String assetType, String component,
        String title, String symptom, String confirmedCause, List<String> diagnosisSteps,
        String remedy, String verificationCriterion, String sourceWorkOrderNo,
        String status, String verifiedBy, Instant verifiedAt,
        Instant createdAt, Instant updatedAt, Long version
) {
    public KnowledgeCase {
        diagnosisSteps = diagnosisSteps == null ? List.of() : List.copyOf(diagnosisSteps);
    }

    public static KnowledgeCase create(String caseNo, String faultCode, String assetType,
                                       String component, String title, String symptom,
                                       String cause, List<String> steps, String remedy,
                                       String criterion, String sourceWorkOrderNo, Instant now) {
        if (steps == null || steps.isEmpty()) throw new IllegalArgumentException("至少需要一个诊断步骤");
        return new KnowledgeCase(required(caseNo, "案例编号"), required(faultCode, "故障编码").toUpperCase(),
                required(assetType, "设备类型"), required(component, "部件"), required(title, "案例标题"),
                required(symptom, "故障现象"), required(cause, "确认原因"), steps,
                required(remedy, "处置方法"), required(criterion, "验证标准"),
                fallback(sourceWorkOrderNo, "人工案例"), "DRAFT", null, null, now, now, null);
    }

    public KnowledgeCase verify(String verifier, Instant now) {
        if ("VERIFIED".equals(status)) throw new DomainConflictException("案例已经审核，不能重复覆盖审核记录");
        return new KnowledgeCase(caseNo, faultCode, assetType, component, title, symptom,
                confirmedCause, diagnosisSteps, remedy, verificationCriterion, sourceWorkOrderNo,
                "VERIFIED", fallback(verifier, "设备主管"), now, createdAt, now, version);
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
