package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class InspectionTaskTest {
    private static final Instant NOW = Instant.parse("2026-09-08T00:00:00Z");

    @Test
    void cannotSubmitBeforeTaskStarts() {
        InspectionTask task = task();
        assertThrows(DomainConflictException.class,
                () -> task.submit(passItems(), "正常", "点检员", NOW));
    }

    @Test
    void allPassingItemsCloseTask() {
        InspectionTask closed = task().start("点检员", NOW)
                .submit(passItems(), "各项符合标准", "点检员", NOW.plusSeconds(60));
        assertEquals(InspectionTaskStatus.CLOSED, closed.status());
        assertEquals(0, closed.failedItems().size());
    }

    @Test
    void failedItemCreatesDefectReadyTask() {
        List<InspectionTask.ItemSubmission> values = List.of(
                new InspectionTask.ItemSubmission("VIB", "7.6", "FAIL", "超过上限"),
                new InspectionTask.ItemSubmission("OIL", "正常", "PASS", "无泄漏"));
        InspectionTask failed = task().start("点检员", NOW)
                .submit(values, "振动超限，需要处理", "点检员", NOW.plusSeconds(60));
        assertEquals(InspectionTaskStatus.DEFECT_FOUND, failed.status());
        assertEquals(1, failed.failedItems().size());
    }

    @Test
    void requiredItemCannotBeOmitted() {
        InspectionTask started = task().start("点检员", NOW);
        assertThrows(IllegalArgumentException.class, () -> started.submit(List.of(
                new InspectionTask.ItemSubmission("VIB", "6.0", "PASS", "正常")),
                "提交", "点检员", NOW.plusSeconds(60)));
    }

    private InspectionTask task() {
        return InspectionTask.create("INS-1", MaintenancePlanTest.plan(), NOW, "机修二班", NOW);
    }

    private List<InspectionTask.ItemSubmission> passItems() {
        return List.of(
                new InspectionTask.ItemSubmission("VIB", "6.0", "PASS", "低于上限"),
                new InspectionTask.ItemSubmission("OIL", "正常", "PASS", "无泄漏"));
    }
}
