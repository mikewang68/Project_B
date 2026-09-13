package com.bproject.ehm.health.domain.model;

public record RulPrediction(
        String status,
        String modelVersion,
        Integer lowerDays,
        Integer expectedDays,
        Integer upperDays,
        double confidencePercent,
        String riskLevel,
        String faultMode,
        String maintenanceWindow,
        String recommendation,
        String limitation
) {
    public static RulPrediction unavailable(String reason) {
        return new RulPrediction("UNAVAILABLE", "not-enabled", null, null, null,
                0, "待评估", "失效模式待确认", "待确认", "补齐退化样本与维修标签后再运行RUL模型", reason);
    }
}
