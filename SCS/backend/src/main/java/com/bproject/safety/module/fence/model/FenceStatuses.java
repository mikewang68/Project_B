package com.bproject.safety.module.fence.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * 围栏生命周期状态机器 Code（F-01）。内部判断一律使用 code，中文标签由 {@link #label(String)} 派生，
 * 兼容前端 types/fence.ts 的中文展示。
 */
public final class FenceStatuses {

    public static final String DRAFT = "DRAFT";
    public static final String TO_REVIEW = "TO_REVIEW";
    public static final String TO_PUBLISH = "TO_PUBLISH";
    public static final String EFFECTIVE = "EFFECTIVE";
    public static final String DISABLED = "DISABLED";
    public static final String MISMATCH = "MISMATCH";

    private static final Map<String, String> LABELS = Map.of(
            DRAFT, "草稿",
            TO_REVIEW, "待评审",
            TO_PUBLISH, "待发布",
            EFFECTIVE, "已生效",
            DISABLED, "已停用",
            MISMATCH, "版本不一致");
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private FenceStatuses() {
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
