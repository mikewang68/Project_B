package com.bproject.ehm.spares.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SpareReservationTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void partialIssueKeepsRemainingReservation() {
        SpareReservation value = reservation().issue(2, NOW.plusSeconds(1));
        assertEquals("PARTIALLY_ISSUED", value.status());
        assertEquals(3, value.remainingQuantity());
    }

    @Test
    void fullIssueEndsReservation() {
        SpareReservation value = reservation().issue(5, NOW.plusSeconds(1));
        assertEquals("ISSUED", value.status());
        assertEquals(0, value.remainingQuantity());
    }

    @Test
    void cannotIssueMoreThanRemaining() {
        assertThrows(DomainConflictException.class, () -> reservation().issue(6, NOW));
    }

    @Test
    void releaseEndsReservationAndCannotRepeat() {
        SpareReservation released = reservation().issue(2, NOW).release("工单方案调整", NOW.plusSeconds(1));
        assertEquals("RELEASED", released.status());
        assertEquals(0, released.remainingQuantity());
        assertThrows(DomainConflictException.class, () -> released.release("重复释放", NOW.plusSeconds(2)));
    }

    private SpareReservation reservation() {
        SparePart part = SparePart.create("BRK-01", "制动器摩擦片", "GT系列", "制动系统", "片",
                List.of("GT-01"), 2, 4, 15, BigDecimal.valueOf(680), NOW);
        return SpareReservation.create("RSV-001", "WO-001", "GT-01", "MAIN", part,
                5, "制动器检修", "库管员", NOW);
    }
}
