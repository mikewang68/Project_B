package com.bproject.ehm.maintenance.domain.model;

import com.bproject.ehm.shared.error.ValidationException;

import java.util.Arrays;

public enum WorkOrderStatus {
    DRAFT("草稿"),
    SUBMITTED("待审批"),
    APPROVED("待执行"),
    IN_PROGRESS("执行中"),
    WAITING_VERIFY("待复测"),
    CLOSED("已关闭"),
    REJECTED("已驳回"),
    CANCELLED("已取消"),
    ON_HOLD("已暂停");

    private final String label;

    WorkOrderStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static WorkOrderStatus from(String value) {
        if (value == null || value.isBlank()) throw new ValidationException("工单状态不能为空");
        return Arrays.stream(values())
                .filter(status -> status.name().equalsIgnoreCase(value) || status.label.equals(value.trim()))
                .findFirst()
                .orElseThrow(() -> new ValidationException("不支持的工单状态：" + value));
    }
}
