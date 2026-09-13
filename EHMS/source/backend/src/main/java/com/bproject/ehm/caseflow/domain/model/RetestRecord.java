package com.bproject.ehm.caseflow.domain.model;

import java.time.Instant;

public record RetestRecord(
        String retestId,
        String pointCode,
        Double beforeValue,
        Double afterValue,
        String unit,
        String criterion,
        boolean passed,
        String operator,
        Instant testedAt
) {
}
