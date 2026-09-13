package com.bproject.ehm.alarm.domain.model;

import java.time.Instant;

public record AlarmTransition(
        AlarmStatus from,
        AlarmStatus to,
        String operator,
        String reason,
        Instant occurredAt
) {
}
