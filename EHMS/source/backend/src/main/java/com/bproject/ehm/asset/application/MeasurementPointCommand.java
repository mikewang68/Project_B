package com.bproject.ehm.asset.application;

public record MeasurementPointCommand(
        String code,
        String componentCode,
        String name,
        String metric,
        String unit,
        String sourceProtocol,
        String sourceAddress,
        Integer sampleIntervalSeconds,
        Double lowerLimit,
        Double upperLimit,
        Boolean enabled
) {
}
