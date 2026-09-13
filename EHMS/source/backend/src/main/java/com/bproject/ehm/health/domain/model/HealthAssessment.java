package com.bproject.ehm.health.domain.model;

import com.bproject.ehm.shared.error.DomainConflictException;

import java.time.Instant;
import java.util.List;
import java.util.Set;

public record HealthAssessment(
        String assessmentId,
        String assetCode,
        String assetName,
        Integer healthScore,
        String healthGrade,
        double confidencePercent,
        String status,
        String method,
        String modelVersion,
        String featureVersion,
        double dataAvailabilityPercent,
        List<HealthFactor> factors,
        RulPrediction prediction,
        List<String> limitations,
        Instant inputWindowStart,
        Instant inputWindowEnd,
        Instant generatedAt,
        Instant validUntil,
        PredictionReview review,
        String workOrderNo,
        Long version
) {
    private static final Set<String> REVIEW_DECISIONS = Set.of("ACCEPTED", "OBSERVE", "REJECTED");

    public HealthAssessment {
        factors = factors == null ? List.of() : List.copyOf(factors);
        limitations = limitations == null ? List.of() : List.copyOf(limitations);
    }

    public static HealthAssessment completed(String assessmentId, String assetCode, String assetName,
                                             int healthScore, double confidencePercent, String method,
                                             String modelVersion, String featureVersion,
                                             double dataAvailabilityPercent, List<HealthFactor> factors,
                                             RulPrediction prediction, List<String> limitations,
                                             Instant inputWindowStart, Instant generatedAt, Instant validUntil) {
        int score = Math.max(0, Math.min(100, healthScore));
        return new HealthAssessment(required(assessmentId, "评估编号"), required(assetCode, "设备编码"),
                required(assetName, "设备名称"), score, grade(score), clamp(confidencePercent), "COMPLETED",
                required(method, "评估方法"), required(modelVersion, "模型版本"),
                required(featureVersion, "特征版本"), clamp(dataAvailabilityPercent), factors,
                prediction == null ? RulPrediction.unavailable("未生成寿命预测") : prediction,
                limitations, inputWindowStart, generatedAt, generatedAt, validUntil, null, null, null);
    }

    public HealthAssessment review(String decision, String comment, String reviewer, Instant now) {
        if (!"COMPLETED".equals(status)) throw new DomainConflictException("只有已完成的评估可以人工审核");
        String normalized = required(decision, "审核决定").toUpperCase();
        if (!REVIEW_DECISIONS.contains(normalized)) {
            throw new IllegalArgumentException("审核决定仅允许 ACCEPTED、OBSERVE 或 REJECTED");
        }
        if (review != null) throw new DomainConflictException("该评估已经完成人工审核，不允许覆盖原审核记录");
        return copy(new PredictionReview(normalized, required(comment, "审核意见"),
                fallback(reviewer, "Demo设备工程师"), now), workOrderNo, now);
    }

    public HealthAssessment linkWorkOrder(String orderNo, Instant now) {
        if (review == null || !"ACCEPTED".equals(review.decision())) {
            throw new DomainConflictException("只有人工审核为接受的预测建议才能转工单");
        }
        if (workOrderNo != null && !workOrderNo.isBlank()) {
            if (workOrderNo.equals(orderNo)) return this;
            throw new DomainConflictException("该评估已关联工单：" + workOrderNo);
        }
        return copy(review, required(orderNo, "工单编号"), now);
    }

    private HealthAssessment copy(PredictionReview nextReview, String nextWorkOrderNo, Instant now) {
        return new HealthAssessment(assessmentId, assetCode, assetName, healthScore, healthGrade,
                confidencePercent, status, method, modelVersion, featureVersion, dataAvailabilityPercent,
                factors, prediction, limitations, inputWindowStart, inputWindowEnd, generatedAt,
                validUntil, nextReview, nextWorkOrderNo, version);
    }

    private static String grade(int score) {
        if (score >= 85) return "健康";
        if (score >= 70) return "关注";
        if (score >= 50) return "异常";
        return "严重";
    }

    private static double clamp(double value) {
        return Math.round(Math.max(0, Math.min(100, value)) * 10.0) / 10.0;
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + "不能为空");
        return value.trim();
    }

    private static String fallback(String value, String replacement) {
        return value == null || value.isBlank() ? replacement : value.trim();
    }
}
