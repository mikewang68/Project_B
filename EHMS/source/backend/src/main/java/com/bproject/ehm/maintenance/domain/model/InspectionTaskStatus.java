package com.bproject.ehm.maintenance.domain.model;

public enum InspectionTaskStatus {
    PLANNED("待执行"),
    IN_PROGRESS("执行中"),
    CLOSED("已完成"),
    DEFECT_FOUND("发现缺陷"),
    CANCELLED("已取消");

    private final String label;

    InspectionTaskStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static InspectionTaskStatus from(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("任务状态不能为空");
        for (InspectionTaskStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.label.equals(value.trim())) return status;
        }
        throw new IllegalArgumentException("未知点检状态：" + value);
    }
}
