package com.bproject.ehm.maintenance.domain.model;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DefectRecordTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void linksInspectionDefectToWorkOrder() {
        InspectionTask task = InspectionTask.create("INS-1", MaintenancePlanTest.plan(), NOW,
                        "机修二班", NOW).start("点检员", NOW)
                .submit(List.of(
                        new InspectionTask.ItemSubmission("VIB", "7.6", "FAIL", "超限"),
                        new InspectionTask.ItemSubmission("OIL", "正常", "PASS", "无泄漏")),
                        "存在振动异常", "点检员", NOW.plusSeconds(60));
        DefectRecord linked = DefectRecord.open("DEF-1", task, "严重", "振动超限",
                "7.6mm/s，高于7.1mm/s", "点检员", NOW).linkWorkOrder("WO-1", NOW.plusSeconds(1));
        assertEquals("WORK_ORDER_CREATED", linked.status());
        assertEquals("WO-1", linked.workOrderNo());
    }
}
