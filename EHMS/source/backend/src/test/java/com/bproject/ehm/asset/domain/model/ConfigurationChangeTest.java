package com.bproject.ehm.asset.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationChangeTest {
    @Test void changeMustBeApprovedBeforeApplying() {
        Instant now = Instant.parse("2026-09-24T02:00:00Z");
        ConfigurationChange draft = ConfigurationChange.create("CFG-1", "GT-01", "POINT", "VIB",
                "采样变更", Map.of("hz", 12800), Map.of("hz", 25600), "提高分辨率", "带宽已核验",
                "工程师", "baseline-1", now);
        assertThrows(DomainConflictException.class, () -> draft.apply("管理员", now));
        ConfigurationChange effective = draft.approve("主管", now.plusSeconds(30))
                .apply("管理员", now.plusSeconds(60));
        assertEquals("EFFECTIVE", effective.status());
        assertEquals(12800, effective.beforeSnapshot().get("hz"));
    }
}
