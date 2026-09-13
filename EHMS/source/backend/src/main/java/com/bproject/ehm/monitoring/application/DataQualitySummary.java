package com.bproject.ehm.monitoring.application;

import java.time.Instant;

public record DataQualitySummary(
        String assetCode,
        long totalPoints,
        long enabledPoints,
        long goodPoints,
        long delayedPoints,
        long missingPoints,
        long outOfRangePoints,
        long invalidPoints,
        long disabledPoints,
        double availabilityPercent,
        boolean healthAssessmentAllowed,
        String assessmentMessage,
        Instant assessedAt
) {
}
