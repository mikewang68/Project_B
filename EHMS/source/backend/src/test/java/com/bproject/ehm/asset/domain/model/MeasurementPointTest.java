package com.bproject.ehm.asset.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MeasurementPointTest {
    private static final Instant NOW = Instant.parse("2026-09-07T00:00:00Z");

    @Test
    void normalizesCodesAndAppliesDefaults() {
        MeasurementPoint point = MeasurementPoint.create(" gt01-temp ", "gt-01", "gt01-gear", "轴承温度",
                "温度", "℃", null, null, null, -20.0, 80.0, null, NOW);

        assertEquals("GT01-TEMP", point.code());
        assertEquals("GT-01", point.assetCode());
        assertEquals("GT01-GEAR", point.componentCode());
        assertEquals(5, point.sampleIntervalSeconds());
    }

    @Test
    void rejectsInvalidRange() {
        assertThrows(IllegalArgumentException.class, () -> MeasurementPoint.create("P-1", "A-1", "C-1", "温度",
                "温度", "℃", "OPC UA", "node", 5, 80.0, 20.0, true, NOW));
    }
}
