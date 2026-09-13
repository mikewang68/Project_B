package com.bproject.ehm.alarm.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public record Alarm(
        String alarmNo,
        String deviceCode,
        String deviceName,
        String component,
        String level,
        String levelClass,
        String summary,
        AlarmStatus status,
        String slaText,
        String triggerMethod,
        Instant occurredAt,
        Instant acknowledgedAt,
        Instant closedAt,
        String assignee,
        List<AlarmTransition> history,
        Long version
) {
    public Alarm {
        history = history == null ? List.of() : List.copyOf(history);
    }

    public Alarm acknowledge(String operator, Instant now) {
        if (status == AlarmStatus.CLOSED || status == AlarmStatus.INVALID || status == AlarmStatus.SUPPRESSED) {
            throw new DomainConflictException("当前状态“" + status.label() + "”不能确认");
        }
        if (status == AlarmStatus.ACKNOWLEDGED || status == AlarmStatus.INVESTIGATING
                || status == AlarmStatus.WAITING_VERIFICATION) return this;
        return transition(AlarmStatus.ACKNOWLEDGED, operator, "人工确认告警", now,
                now, closedAt, assignee(operator));
    }

    public Alarm close(String operator, String reason, Instant now) {
        if (status == AlarmStatus.CLOSED) return this;
        if (status == AlarmStatus.NEW || status == AlarmStatus.PENDING_ACKNOWLEDGEMENT) {
            throw new DomainConflictException("告警必须先确认，不能从“" + status.label() + "”直接关闭");
        }
        if (status == AlarmStatus.INVALID || status == AlarmStatus.SUPPRESSED) {
            throw new DomainConflictException("当前状态“" + status.label() + "”不能关闭");
        }
        String closeReason = reason == null || reason.isBlank() ? "Demo人工关闭" : reason.trim();
        return transition(AlarmStatus.CLOSED, operator, closeReason, now,
                acknowledgedAt == null ? now : acknowledgedAt, now, assignee(operator));
    }

    private Alarm transition(AlarmStatus target, String operator, String reason, Instant now,
                             Instant acknowledgedAt, Instant closedAt, String assignee) {
        List<AlarmTransition> nextHistory = new ArrayList<>(history);
        nextHistory.add(new AlarmTransition(status, target, assignee(operator), reason, now));
        return new Alarm(alarmNo, deviceCode, deviceName, component, level, levelClass, summary,
                target, target == AlarmStatus.CLOSED ? "已闭环" : slaText, triggerMethod, occurredAt,
                acknowledgedAt, closedAt, assignee, nextHistory, version);
    }

    private String assignee(String operator) {
        return operator == null || operator.isBlank() ? "Demo设备管理员" : operator.trim();
    }
}
