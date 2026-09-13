package com.bproject.ehm.alarm.adapter.out.mongo;

import com.bproject.ehm.alarm.domain.model.Alarm;
import com.bproject.ehm.alarm.domain.model.AlarmStatus;
import com.bproject.ehm.alarm.domain.model.AlarmTransition;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("alarms_v2")
public class AlarmDocument {
    @Id private final String alarmNo;
    private final String deviceCode;
    private final String deviceName;
    private final String component;
    private final String level;
    private final String levelClass;
    private final String summary;
    private final String status;
    private final String slaText;
    private final String triggerMethod;
    private final Instant occurredAt;
    private final Instant acknowledgedAt;
    private final Instant closedAt;
    private final String assignee;
    private final List<AlarmTransition> history;
    @Version private final Long version;

    public AlarmDocument(String alarmNo, String deviceCode, String deviceName, String component, String level,
                         String levelClass, String summary, String status, String slaText, String triggerMethod,
                         Instant occurredAt, Instant acknowledgedAt, Instant closedAt, String assignee,
                         List<AlarmTransition> history, Long version) {
        this.alarmNo = alarmNo;
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.component = component;
        this.level = level;
        this.levelClass = levelClass;
        this.summary = summary;
        this.status = status;
        this.slaText = slaText;
        this.triggerMethod = triggerMethod;
        this.occurredAt = occurredAt;
        this.acknowledgedAt = acknowledgedAt;
        this.closedAt = closedAt;
        this.assignee = assignee;
        this.history = history;
        this.version = version;
    }

    static AlarmDocument fromDomain(Alarm alarm) {
        return new AlarmDocument(alarm.alarmNo(), alarm.deviceCode(), alarm.deviceName(), alarm.component(),
                alarm.level(), alarm.levelClass(), alarm.summary(), alarm.status().name(), alarm.slaText(),
                alarm.triggerMethod(), alarm.occurredAt(), alarm.acknowledgedAt(), alarm.closedAt(),
                alarm.assignee(), alarm.history(), alarm.version());
    }

    Alarm toDomain() {
        return new Alarm(alarmNo, deviceCode, deviceName, component, level, levelClass, summary,
                AlarmStatus.from(status), slaText, triggerMethod, occurredAt, acknowledgedAt, closedAt,
                assignee, history, version);
    }
}
