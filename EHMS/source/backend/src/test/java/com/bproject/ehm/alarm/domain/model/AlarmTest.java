package com.bproject.ehm.alarm.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AlarmTest {
    private static final Instant NOW = Instant.parse("2026-09-07T02:00:00Z");

    @Test
    void newAlarmMustBeAcknowledgedBeforeClosing() {
        Alarm alarm = alarm(AlarmStatus.NEW);
        assertThrows(DomainConflictException.class, () -> alarm.close("tester", "已处理", NOW));

        Alarm acknowledged = alarm.acknowledge("tester", NOW);
        Alarm closed = acknowledged.close("tester", "复测正常", NOW.plusSeconds(60));

        assertEquals(AlarmStatus.CLOSED, closed.status());
        assertEquals(2, closed.history().size());
        assertEquals("复测正常", closed.history().get(1).reason());
    }

    @Test
    void closedAlarmCannotBeAcknowledgedAgain() {
        assertThrows(DomainConflictException.class,
                () -> alarm(AlarmStatus.CLOSED).acknowledge("tester", NOW));
    }

    private Alarm alarm(AlarmStatus status) {
        return new Alarm("ALM-1", "GT-01", "门吊", "减速机", "L3", "severe", "异常",
                status, "未超期", "规则", NOW.minusSeconds(60), null, null, "机修班", List.of(), 0L);
    }
}
