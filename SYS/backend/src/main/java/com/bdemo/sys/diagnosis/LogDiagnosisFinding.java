package com.bdemo.sys.diagnosis;

import java.util.List;

/**
 * 一条日志诊断发现（结构化、可解释、可下钻到证据日志）。
 */
public class LogDiagnosisFinding {

    private String ruleId;
    /** HIGH / MEDIUM / LOW / INFO */
    private String severity;
    private String title;
    private String description;
    /** 证据日志 ID，前端可点击定位筛选 */
    private List<String> evidenceLogIds;
    private String recommendation;

    public LogDiagnosisFinding() {
    }

    public LogDiagnosisFinding(String ruleId, String severity, String title, String description,
                               List<String> evidenceLogIds, String recommendation) {
        this.ruleId = ruleId;
        this.severity = severity;
        this.title = title;
        this.description = description;
        this.evidenceLogIds = evidenceLogIds;
        this.recommendation = recommendation;
    }

    public String getRuleId() {
        return ruleId;
    }

    public void setRuleId(String ruleId) {
        this.ruleId = ruleId;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<String> getEvidenceLogIds() {
        return evidenceLogIds;
    }

    public void setEvidenceLogIds(List<String> evidenceLogIds) {
        this.evidenceLogIds = evidenceLogIds;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }
}
