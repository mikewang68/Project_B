package com.bproject.ehm.caseflow.domain.model;

import java.time.Instant;

public record ExecutionRecord(
        String recordId,
        String action,
        String result,
        String safetyConfirmation,
        String partsUsed,
        String operator,
        Instant executedAt
) {
}
