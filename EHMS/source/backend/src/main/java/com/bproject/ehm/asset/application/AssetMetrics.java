package com.bproject.ehm.asset.application;

public record AssetMetrics(
        long total,
        long online,
        long assessable,
        long healthy,
        long highRisk,
        double averageHealth,
        double averageQuality
) {
}
