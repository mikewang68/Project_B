package com.bproject.ehm.spares.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StockBalanceTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void receiptIncreasesOnHandAndAvailableQuantity() {
        StockBalance value = StockBalance.empty("MAIN", "BRK-01", NOW).receive(8, NOW);
        assertEquals(8, value.onHandQuantity());
        assertEquals(8, value.availableQuantity());
    }

    @Test
    void reservationReducesOnlyAvailableQuantity() {
        StockBalance value = stocked().reserve(3, NOW);
        assertEquals(8, value.onHandQuantity());
        assertEquals(3, value.reservedQuantity());
        assertEquals(5, value.availableQuantity());
    }

    @Test
    void cannotReserveMoreThanAvailable() {
        assertThrows(DomainConflictException.class, () -> stocked().reserve(9, NOW));
    }

    @Test
    void issueReducesOnHandAndReservedTogether() {
        StockBalance value = stocked().reserve(4, NOW).issue(2, NOW);
        assertEquals(6, value.onHandQuantity());
        assertEquals(2, value.reservedQuantity());
        assertEquals(4, value.availableQuantity());
    }

    @Test
    void releaseKeepsOnHandAndRestoresAvailable() {
        StockBalance value = stocked().reserve(4, NOW).release(3, NOW);
        assertEquals(8, value.onHandQuantity());
        assertEquals(1, value.reservedQuantity());
        assertEquals(7, value.availableQuantity());
    }

    private StockBalance stocked() {
        return StockBalance.empty("MAIN", "BRK-01", NOW).receive(8, NOW);
    }
}
