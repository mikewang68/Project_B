package com.bproject.ehm.reliability.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class FailureModeTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void calculatesRpnFromThreeScores() {
        FailureMode value = mode(8, 5, 4);
        assertEquals(160, value.rpn());
    }

    @Test
    void rejectsScoreOutsideOneToTen() {
        assertThrows(IllegalArgumentException.class, () -> mode(11, 5, 4));
    }

    private FailureMode mode(int severity, int occurrence, int detectability) {
        return FailureMode.create("FM-1", "GT-GEAR-001", "门式起重机", "起升减速机",
                "轴承磨损", "振动和温升", "润滑劣化", severity, occurrence, detectability,
                "振动监测", "油样复核", "设备工程师", NOW);
    }
}
