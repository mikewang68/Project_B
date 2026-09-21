package com.bproject.safety.module.rule.model;

import com.bproject.safety.module.alert.model.RiskLevels;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 规则生命周期状态机器 Code（F-01）。内部判断一律使用 code，中文标签由 {@link #label(String)} 派生。
 * Demo 状态机，不做最终状态机设计。
 */
public final class RuleStatuses {

    public static final String DRAFT = "DRAFT";
    public static final String REVIEW = "REVIEW";
    public static final String APPROVED = "APPROVED";
    public static final String PUBLISHING = "PUBLISHING";
    public static final String ACTIVE = "ACTIVE";
    public static final String MISMATCH = "MISMATCH";
    public static final String DISABLED = "DISABLED";

    private static final Map<String, String> LABELS = Map.of(
            DRAFT, "草稿",
            REVIEW, "待评审",
            APPROVED, "已批准",
            PUBLISHING, "发布中",
            ACTIVE, "已生效",
            MISMATCH, "版本异常",
            DISABLED, "已停用");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    /** 边缘节点同步状态（保持英文 code）。 */
    public static final String EDGE_SYNCED = "synced";
    public static final String EDGE_SYNCING = "syncing";
    public static final String EDGE_MISMATCH = "mismatch";

    /** 统一风险等级顺序（机器 code，复用 Alert 风险词表）。 */
    public static final List<String> RISK_LEVELS = List.of(
            RiskLevels.NORMAL, RiskLevels.WARNING, RiskLevels.SEVERE, RiskLevels.URGENT);

    private RuleStatuses() {
    }

    public static String label(String code) {
        return code == null ? null : LABELS.get(code);
    }

    public static String fromLabel(String label) {
        return label == null ? null : BY_LABEL.get(label);
    }

    /** 入参可能是 code 或旧中文标签，统一归一为 code。 */
    public static String normalize(String codeOrLabel) {
        if (codeOrLabel == null || codeOrLabel.isBlank()) {
            return null;
        }
        return LABELS.containsKey(codeOrLabel) ? codeOrLabel : BY_LABEL.get(codeOrLabel);
    }
}
