package com.bdemo.iam.health;

import java.util.List;

/**
 * 一条权限健康诊断发现（结构化、可解释）。
 */
public class HealthFinding {

    private String ruleId;
    /** HIGH / MEDIUM / LOW */
    private String severity;
    private String type;
    private String title;
    private String description;
    /** 具体证据（角色/权限/用户等） */
    private List<String> evidence;
    private String recommendation;

    public HealthFinding() {
    }

    public HealthFinding(String ruleId, String severity, String type, String title,
                         String description, List<String> evidence, String recommendation) {
        this.ruleId = ruleId;
        this.severity = severity;
        this.type = type;
        this.title = title;
        this.description = description;
        this.evidence = evidence;
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

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
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

    public List<String> getEvidence() {
        return evidence;
    }

    public void setEvidence(List<String> evidence) {
        this.evidence = evidence;
    }

    public String getRecommendation() {
        return recommendation;
    }

    public void setRecommendation(String recommendation) {
        this.recommendation = recommendation;
    }
}
