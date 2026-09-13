package com.bproject.ehm.monitoring.application;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;
import com.bproject.ehm.monitoring.domain.model.PointQualitySnapshot;

import java.time.Instant;

public record DataQualityPointView(
        String pointCode,
        String assetCode,
        String componentCode,
        String name,
        String metric,
        String unit,
        String sourceProtocol,
        String sourceAddress,
        int sampleIntervalSeconds,
        Double lowerLimit,
        Double upperLimit,
        boolean enabled,
        String qualityStatus,
        String qualityLabel,
        Double lastValue,
        Instant sourceTimestamp,
        Instant receivedAt,
        Instant assessedAt,
        String message,
        int consecutiveFailures
) {
    public static DataQualityPointView compose(MeasurementPoint point, PointQualitySnapshot quality) {
        return new DataQualityPointView(point.code(), point.assetCode(), point.componentCode(), point.name(),
                point.metric(), point.unit(), point.sourceProtocol(), point.sourceAddress(),
                point.sampleIntervalSeconds(), point.lowerLimit(), point.upperLimit(), point.enabled(),
                quality.status().name(), quality.status().label(), quality.value(), quality.sourceTimestamp(),
                quality.receivedAt(), quality.assessedAt(), quality.message(), quality.consecutiveFailures());
    }
}
