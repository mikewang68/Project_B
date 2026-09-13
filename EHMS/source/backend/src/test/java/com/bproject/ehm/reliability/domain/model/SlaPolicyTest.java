package com.bproject.ehm.reliability.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SlaPolicyTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void acceptsOrderedDeadlines() {
        SlaPolicy value = SlaPolicy.create("L3", 10, 20, 60, "设备主管", NOW);
        assertEquals(60, value.recoverMinutes());
    }

    @Test
    void rejectsDeadlineOrderViolation() {
        assertThrows(IllegalArgumentException.class,
                () -> SlaPolicy.create("L3", 30, 20, 60, "设备主管", NOW));
    }
}
