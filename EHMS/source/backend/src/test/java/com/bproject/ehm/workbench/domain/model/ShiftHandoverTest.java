package com.bproject.ehm.workbench.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ShiftHandoverTest {
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Test void handoverRequiresSubmitBeforeReceiving() {
        ShiftHandover draft = ShiftHandover.create("HO-1", LocalDate.of(2026, 9, 24), "白班", "夜班",
                "白班长", "夜班长", "有一项待办", List.of("GT-01告警"), List.of(), List.of(), null, NOW);
        assertThrows(DomainConflictException.class, () -> draft.receive("夜班长", NOW));
        ShiftHandover received = draft.submit("白班长", NOW.plusSeconds(60))
                .receive("夜班长", NOW.plusSeconds(120));
        assertEquals("RECEIVED", received.status());
    }
}
