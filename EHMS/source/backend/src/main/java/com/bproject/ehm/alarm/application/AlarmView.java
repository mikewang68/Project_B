package com.bproject.ehm.alarm.application;

import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.domain.model.AlarmTransition;

import java.time.Instant;
import java.util.List;

public record AlarmView(
        String alarmNo,
        String deviceCode,
        String deviceName,
        String component,
        String level,
        String levelClass,
        String summary,
        String status,
        String slaText,
        String triggerMethod,
        Instant occurredAt,
        Instant acknowledgedAt,
        Instant closedAt,
        String assignee,
        List<AlarmTransition> history,
        Long version
) {
    static AlarmView from(Alarm alarm) {
        return new AlarmView(alarm.alarmNo(), alarm.deviceCode(), alarm.deviceName(), alarm.component(),
                alarm.level(), alarm.levelClass(), alarm.summary(), alarm.status().label(), alarm.slaText(),
                alarm.triggerMethod(), alarm.occurredAt(), alarm.acknowledgedAt(), alarm.closedAt(),
                alarm.assignee(), alarm.history(), alarm.version());
    }
}
