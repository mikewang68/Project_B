package com.bproject.safety.module.personnel.model;

import java.util.Map;
import java.util.stream.Collectors;

/** 手环状态机器 Code（F-01）：ONLINE/OFFLINE/LOW_BATTERY（在线/离线/低电量）。 */
public final class BraceletStatuses {

    public static final String ONLINE = "ONLINE";
    public static final String OFFLINE = "OFFLINE";
    public static final String LOW_BATTERY = "LOW_BATTERY";

    private static final Map<String, String> LABELS = Map.of(
            ONLINE, "在线", OFFLINE, "离线", LOW_BATTERY, "低电量");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private BraceletStatuses() {
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
}
