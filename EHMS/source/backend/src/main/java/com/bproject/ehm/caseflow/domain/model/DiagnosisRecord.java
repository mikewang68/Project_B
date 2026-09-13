package com.bproject.ehm.caseflow.domain.model;

import java.time.Instant;

public record DiagnosisRecord(
        String diagnosisId,
        String conclusion,
        String probableCause,
        String confidence,
        String evidence,
        String operator,
        Instant createdAt
) {
}
