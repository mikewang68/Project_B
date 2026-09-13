package com.bproject.ehm.monitoring.application;

import java.time.Instant;

public record PointSampleCommand(
        Double value,
        Instant sourceTimestamp,
        Instant receivedAt
) {
}
