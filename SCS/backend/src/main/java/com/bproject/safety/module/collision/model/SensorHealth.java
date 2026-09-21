package com.bproject.safety.module.collision.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 碰撞感知（雷达）健康机器 Code（F-01 语义拆分）。
 *
 * <p>NORMAL 正常 / UNCERTAIN 待确认（雷达断数，无法确认安全距离）/ RADAR_DOWN 雷达故障。
 * “待确认”属于传感器健康态而非碰撞风险等级，风险仍由 {@link CollisionRiskLevels} 表达。</p>
 */
public final class SensorHealth {

    public static final String NORMAL = "NORMAL";
    public static final String UNCERTAIN = "UNCERTAIN";
    public static final String RADAR_DOWN = "RADAR_DOWN";

    private static final Map<String, String> LABELS = Map.of(
            NORMAL, "正常", UNCERTAIN, "待确认", RADAR_DOWN, "雷达故障");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private SensorHealth() {
    }

    public static String label(String code) {
        return code == null ? null : LABELS.get(code);
    }

    public static String fromLabel(String label) {
        return label == null ? null : BY_LABEL.get(label);
    }
}
