package com.bdemo.iam.health;

import java.util.List;

/**
 * 权限健康诊断报告：概览 + 发现列表。
 */
public class PermissionHealthReport {

    private HealthSummary summary;
    private List<HealthFinding> findings;

    public PermissionHealthReport() {
    }

    public PermissionHealthReport(HealthSummary summary, List<HealthFinding> findings) {
        this.summary = summary;
        this.findings = findings;
    }

    public HealthSummary getSummary() {
        return summary;
    }

    public void setSummary(HealthSummary summary) {
        this.summary = summary;
    }

    public List<HealthFinding> getFindings() {
        return findings;
    }

    public void setFindings(List<HealthFinding> findings) {
        this.findings = findings;
    }
}
