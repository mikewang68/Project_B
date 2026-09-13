package com.bproject.ehm.asset.application;

import com.bproject.ehm.asset.domain.model.MeasurementPoint;

import java.time.Instant;

public record MeasurementPointView(
        String code,
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
        Instant updatedAt,
        Long version
) {
    public static MeasurementPointView from(MeasurementPoint point) {
        return new MeasurementPointView(point.code(), point.assetCode(), point.componentCode(), point.name(),
                point.metric(), point.unit(), point.sourceProtocol(), point.sourceAddress(),
                point.sampleIntervalSeconds(), point.lowerLimit(), point.upperLimit(), point.enabled(),
                point.updatedAt(), point.version());
    }
}
