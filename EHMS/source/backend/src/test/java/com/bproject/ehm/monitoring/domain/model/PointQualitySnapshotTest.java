package com.bproject.ehm.monitoring.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PointQualitySnapshotTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void acceptsFreshValueInsideConfiguredRange() {
        PointQualitySnapshot snapshot = assess(5, 6.8, NOW.minusSeconds(1), NOW, 0.0, 7.1);
        assertEquals(QualityStatus.GOOD, snapshot.status());
        assertEquals(0, snapshot.consecutiveFailures());
    }

    @Test
    void identifiesOutOfRangeValue() {
        PointQualitySnapshot snapshot = assess(5, 8.2, NOW.minusSeconds(1), NOW, 0.0, 7.1);
        assertEquals(QualityStatus.OUT_OF_RANGE, snapshot.status());
        assertEquals(1, snapshot.consecutiveFailures());
    }

    @Test
    void identifiesStaleSampleAsMissing() {
        PointQualitySnapshot snapshot = assess(5, 6.8, NOW.minusSeconds(31), NOW.minusSeconds(30), 0.0, 7.1);
        assertEquals(QualityStatus.MISSING, snapshot.status());
    }

    @Test
    void identifiesTransportDelayBeforeFreshnessExpires() {
        PointQualitySnapshot snapshot = assess(1, 6.8, NOW.minusSeconds(10), NOW, 0.0, 7.1);
        assertEquals(QualityStatus.DELAYED, snapshot.status());
    }

    private PointQualitySnapshot assess(int interval, double value, Instant source, Instant received,
                                        Double lower, Double upper) {
        return PointQualitySnapshot.assess("P-1", "A-1", true, interval, lower, upper,
                value, source, received, null, NOW);
    }
}
