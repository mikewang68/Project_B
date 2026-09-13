package com.bproject.ehm.health.domain.model;

import java.time.Instant;

public record PredictionReview(
        String decision,
        String comment,
        String reviewer,
        Instant reviewedAt
) {
}
