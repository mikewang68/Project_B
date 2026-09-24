package com.bproject.ehm.workbench.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UserTaskTest {
    private static final Instant NOW = Instant.parse("2026-09-24T02:00:00Z");

    @Test void completedTaskKeepsCompletionTimeAndRejectsEditing() {
        UserTask task = UserTask.create("task-1", "告警处置", "ALARM", "A-1", "核验告警",
                "现场复核", "gt-01", "闫鑫钰", "机修二班", "P1 高", NOW.plusSeconds(3600), NOW);
        UserTask completed = task.complete("闫鑫钰", NOW.plusSeconds(600));
        assertEquals("COMPLETED", completed.status());
        assertNotNull(completed.completedAt());
        assertThrows(DomainConflictException.class, () -> completed.revise("修改", null, "GT-01",
                "闫鑫钰", "机修二班", "P1 高", NOW, "OPEN", NOW.plusSeconds(700)));
    }
}
