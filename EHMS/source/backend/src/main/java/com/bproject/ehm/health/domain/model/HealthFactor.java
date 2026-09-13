package com.bproject.ehm.health.domain.model;

public record HealthFactor(
        String pointCode,
        String metric,
        Double value,
        String unit,
        Double referenceLimit,
        double utilizationPercent,
        double deduction,
        String explanation
) {
}
