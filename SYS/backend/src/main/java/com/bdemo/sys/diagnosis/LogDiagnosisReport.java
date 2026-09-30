package com.bdemo.sys.diagnosis;

import java.util.List;

/**
 * 日志诊断报告：时间范围 + 汇总 + 发现列表。
 */
public class LogDiagnosisReport {

    private DiagnosisTimeRange timeRange;
    private DiagnosisSummary summary;
    private List<LogDiagnosisFinding> findings;

    public LogDiagnosisReport() {
    }

    public LogDiagnosisReport(DiagnosisTimeRange timeRange, DiagnosisSummary summary,
                              List<LogDiagnosisFinding> findings) {
        this.timeRange = timeRange;
        this.summary = summary;
        this.findings = findings;
    }

    public DiagnosisTimeRange getTimeRange() {
        return timeRange;
    }

    public void setTimeRange(DiagnosisTimeRange timeRange) {
        this.timeRange = timeRange;
    }

    public DiagnosisSummary getSummary() {
        return summary;
    }

    public void setSummary(DiagnosisSummary summary) {
        this.summary = summary;
    }

    public List<LogDiagnosisFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<LogDiagnosisFinding> findings) {
        this.findings = findings;
    }
}
