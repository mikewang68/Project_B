package com.bproject.safety.module.collision.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 防碰撞风险机器 Code（F-01）：SAFE/WARNING/SEVERE/URGENT（安全/预警/严重/紧急）。
 *
 * <p>雷达断数导致的“待确认”不是碰撞风险等级，而是传感器健康问题，见 {@link SensorHealth}，
 * 不得再把 UNCERTAIN 当作风险等级参与建单 / 升级判断。</p>
 */
public final class CollisionRiskLevels {

    public static final String SAFE = "SAFE";
    public static final String WARNING = "WARNING";
    public static final String SEVERE = "SEVERE";
    public static final String URGENT = "URGENT";

    private static final Map<String, String> LABELS = Map.of(
            SAFE, "安全", WARNING, "预警", SEVERE, "严重", URGENT, "紧急");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private CollisionRiskLevels() {
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
        return LABELS.containsKey(codeOrLabel) ? codeOrLabel : BY_LABEL.get(codeOrLabel);
    }

    /** 碰撞风险 code → 告警风险 code（两词表当前一一对应，集中映射避免散落）。 */
    public static String toAlertRiskCode(String collisionRiskCode) {
        return switch (collisionRiskCode) {
            case URGENT -> com.bproject.safety.module.alert.model.RiskLevels.URGENT;
            case SEVERE -> com.bproject.safety.module.alert.model.RiskLevels.SEVERE;
            case WARNING -> com.bproject.safety.module.alert.model.RiskLevels.WARNING;
            default -> com.bproject.safety.module.alert.model.RiskLevels.NORMAL;
        };
    }
}
