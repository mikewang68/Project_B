package com.bproject.ehm.alarm.domain.model;

import com.bproject.ehm.shared.error.ValidationException;

import java.util.Arrays;

public enum AlarmStatus {
    NEW("新建"),
    PENDING_ACKNOWLEDGEMENT("待确认"),
    ACKNOWLEDGED("已确认"),
    INVESTIGATING("处理中"),
    WAITING_VERIFICATION("待验证"),
    CLOSED("已关闭"),
    SUPPRESSED("已抑制"),
    INVALID("无效告警");

    private final String label;

    AlarmStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static AlarmStatus from(String value) {
        if (value == null || value.isBlank()) throw new ValidationException("告警状态不能为空");
        return Arrays.stream(values())
                .filter(status -> status.name().equalsIgnoreCase(value) || status.label.equals(value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("不支持的告警状态：" + value));
    }
}
