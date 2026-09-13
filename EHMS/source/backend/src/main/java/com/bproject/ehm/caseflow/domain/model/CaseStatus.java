package com.bproject.ehm.caseflow.domain.model;

public enum CaseStatus {
    OPEN("待诊断"),
    DIAGNOSED("已诊断"),
    WORK_ORDER_CREATED("已转工单"),
    EXECUTING("处置中"),
    WAITING_RETEST("待复测"),
    VERIFIED("复测通过"),
    CLOSED("已闭环");

    private final String label;

    CaseStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static CaseStatus from(String value) {
        if (value == null || value.isBlank()) return OPEN;
        for (CaseStatus status : values()) {
            if (status.name().equalsIgnoreCase(value) || status.label.equals(value)) return status;
        }
        throw new IllegalArgumentException("未知闭环状态：" + value);
    }
}
