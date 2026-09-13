package com.bproject.ehm.maintenance.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MaintenancePlanTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void generatingTaskAdvancesNextDueDate() {
        MaintenancePlan generated = plan().markGenerated(NOW);
        assertEquals(NOW, generated.lastGeneratedAt());
        assertEquals(NOW.plus(7, ChronoUnit.DAYS), generated.nextDueAt());
    }

    @Test
    void planRequiresChecklist() {
        assertThrows(IllegalArgumentException.class, () -> MaintenancePlan.create(
                "MPL-1", "GT-01", "1#门式起重机", "GT01-GEARBOX", "周检",
                "PERIODIC", 7, "每7天", List.of(), "机修班", NOW, NOW));
    }

    static MaintenancePlan plan() {
        return MaintenancePlan.create("MPL-1", "GT-01", "1#门式起重机",
                "GT01-GEARBOX", "起升减速机周检", "CONDITION_BASED", 7,
                "每7天或振动达到6.0mm/s", List.of(
                        new InspectionTemplateItem("VIB", "振动复测", "规定工况采样",
                                "振动速度RMS≤7.1", "mm/s", true),
                        new InspectionTemplateItem("OIL", "润滑检查", "目视和油样",
                                "无泄漏、无明显金属屑", "—", true)),
                "机修二班", NOW, NOW);
    }
}
