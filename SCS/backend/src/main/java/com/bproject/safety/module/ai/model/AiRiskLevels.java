package com.bproject.safety.module.ai.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 事件风险机器 Code（F-01）：LOW / MEDIUM / HIGH（低 / 中 / 高）。
 *
 * <p>AI 风险是三档词表，与告警四档 {@link com.bproject.safety.module.alert.model.RiskLevels}
 * （NORMAL/WARNING/SEVERE/URGENT）不是同一词表，不强行统一；AI → Alert 时集中映射。</p>
 */
public final class AiRiskLevels {

    public static final String LOW = "LOW";
    public static final String MEDIUM = "MEDIUM";
    public static final String HIGH = "HIGH";

    private static final Map<String, String> LABELS = Map.of(
            LOW, "低", MEDIUM, "中", HIGH, "高");

    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private AiRiskLevels() {
    }

    public static String label(String code) {
        return code == null ? null : LABELS.get(code);
    }

    public static String fromLabel(String label) {
        return label == null ? null : BY_LABEL.get(label);
    }

    public static String normalize(String codeOrLabel) {
        if (codeOrLabel == null || codeOrLabel.isBlank()) {
            return null;
        }
        if (LABELS.containsKey(codeOrLabel)) {
            return codeOrLabel;
        }
        return BY_LABEL.get(codeOrLabel);
    }

    public static boolean isHigh(String code) {
        return HIGH.equals(code);
    }

    /**
     * AI 三档风险 → 告警四档风险 code 的集中映射（唯一映射点）。
     * HIGH→SEVERE，MEDIUM→WARNING，LOW→NORMAL。
     */
    public static String toAlertRiskCode(String aiRiskCodeOrLabel) {
        String code = normalize(aiRiskCodeOrLabel);
        if (code == null) {
            return com.bproject.safety.module.alert.model.RiskLevels.WARNING;
        }
        return switch (code) {
            case HIGH -> com.bproject.safety.module.alert.model.RiskLevels.SEVERE;
            case LOW -> com.bproject.safety.module.alert.model.RiskLevels.NORMAL;
            default -> com.bproject.safety.module.alert.model.RiskLevels.WARNING;
        };
    }
}
