package com.bdemo.sys.diagnosis;

/**
 * 诊断时间范围。
 */
public class DiagnosisTimeRange {

    private String from;
    private String to;

    public DiagnosisTimeRange() {
    }

    public DiagnosisTimeRange(String from, String to) {
        this.from = from;
        this.to = to;
    }

    public String getFrom() {
        return from;
    }

    public void setFrom(String from) {
        this.from = from;
    }

    public String getTo() {
        return to;
    }

    public void setTo(String to) {
        this.to = to;
    }
}
