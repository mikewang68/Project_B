package com.bproject.ehm.reliability.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlarmRuleTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void draftCanBePublishedAndThenDisabled() {
        AlarmRule published = rule().publish("主管", NOW.plusSeconds(1));
        assertEquals("PUBLISHED", published.status());
        assertEquals("DISABLED", published.disable("误报复盘", NOW.plusSeconds(2)).status());
    }

    @Test
    void publishedRuleCannotBePublishedTwice() {
        AlarmRule published = rule().publish("主管", NOW);
        assertThrows(DomainConflictException.class, () -> published.publish("主管", NOW));
    }

    @Test
    void rejectsUnsupportedSeverity() {
        assertThrows(IllegalArgumentException.class, () -> AlarmRule.create("R-1", "规则", "门吊",
                "振动", "value>1", "value<1", "P1", 60, "工程师", NOW));
    }

    private AlarmRule rule() {
        return AlarmRule.create("RULE-1", "振动超限", "门吊", "振动RMS",
                "value>7.1", "value<6.0", "L3", 180, "工程师", NOW);
    }
}
