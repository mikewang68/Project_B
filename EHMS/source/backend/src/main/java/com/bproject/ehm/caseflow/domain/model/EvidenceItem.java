package com.bproject.ehm.caseflow.domain.model;

import java.time.Instant;

public record EvidenceItem(
        String type,
        String title,
        String content,
        String sourceRef,
        Instant capturedAt
) {
}
