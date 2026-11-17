package com.bproject.ehm.monitoring.ports;

import java.time.Instant;
import java.util.List;

public interface TelemetrySeriesPort {
    void write(TelemetrySample sample);

    List<TelemetrySample> history(String pointCode, Instant from, Instant to, int limit);

    void ping();

    record TelemetrySample(
            String pointCode,
            String assetCode,
            String metric,
            String unit,
            Double value,
            String quality,
            Instant sourceTimestamp,
            Instant receivedAt,
            String eventId,
            Boolean simulated,
            String observationRef
    ) {
        public TelemetrySample(String pointCode, String assetCode, String metric, String unit, Double value,
                               String quality, Instant sourceTimestamp, Instant receivedAt) {
            this(pointCode, assetCode, metric, unit, value, quality, sourceTimestamp, receivedAt,
                    null, pointCode != null && (pointCode.startsWith("SAMPLE-LIVE-") || pointCode.startsWith("MQ-SIM-")), null);
        }
    }
}
