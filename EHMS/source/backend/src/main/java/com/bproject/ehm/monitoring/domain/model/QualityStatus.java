package com.bproject.ehm.monitoring.domain.model;

public enum QualityStatus {
    GOOD("正常"),
    DELAYED("延迟"),
    MISSING("断流"),
    OUT_OF_RANGE("越界"),
    INVALID("无效"),
    DISABLED("停用");

    private final String label;

    QualityStatus(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public boolean usable() {
        return this == GOOD;
    }
}
