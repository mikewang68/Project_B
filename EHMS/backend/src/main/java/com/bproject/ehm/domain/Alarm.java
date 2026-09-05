package com.bproject.ehm.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("alarms")
public record Alarm(
        @Id String alarmNo,
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
        String assignee
) {
    public Alarm acknowledge(String operator) {
        return new Alarm(alarmNo, deviceCode, deviceName, component, level, levelClass, summary,
                "已确认", slaText, triggerMethod, occurredAt, Instant.now(), closedAt, operator);
    }

    public Alarm close(String operator) {
        return new Alarm(alarmNo, deviceCode, deviceName, component, level, levelClass, summary,
                "已关闭", "已闭环", triggerMethod, occurredAt,
                acknowledgedAt == null ? Instant.now() : acknowledgedAt, Instant.now(), operator);
    }
}
