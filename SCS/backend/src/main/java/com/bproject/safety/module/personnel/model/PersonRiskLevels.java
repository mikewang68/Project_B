package com.bproject.safety.module.personnel.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 人员业务风险机器 Code（F-01）：NORMAL/ATTENTION/HIGH（正常/关注/高风险）。
 *
 * <p>与在岗状态 {@link PersonStatuses}、手环状态 {@link BraceletStatuses} 是不同词表，
 * 不得混入同一 code set；地图 {@code state}（normal/warning/danger/offline）为综合派生态。</p>
 */
public final class PersonRiskLevels {

    public static final String NORMAL = "NORMAL";
    public static final String ATTENTION = "ATTENTION";
    public static final String HIGH = "HIGH";

    private static final Map<String, String> LABELS = Map.of(
            NORMAL, "正常", ATTENTION, "关注", HIGH, "高风险");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private PersonRiskLevels() {
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

    public static boolean isHigh(String code) {
        return HIGH.equals(code);
    }
}
