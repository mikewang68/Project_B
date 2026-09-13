package com.bproject.ehm.monitoring.domain.model;

public record SnapshotMetrics(
        long online,
        long assessable,
        long healthy,
        long highRisk,
        double averageHealth,
        double averageQuality
) {
}
