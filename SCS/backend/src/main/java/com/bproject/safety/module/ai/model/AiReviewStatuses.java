package com.bproject.safety.module.ai.model;

import java.util.Map;
import java.util.stream.Collectors;

/**
 * AI 复核状态机器 Code（F-01）。内部状态机 / 存储使用稳定 code，中文仅为展示标签。
 *
 * <p>AI 状态词表与 {@link com.bproject.safety.module.alert.model.AlertStatuses} 不同，
 * 不强行合并；AI 仅负责识别 / 复核 / 派单入口，确认违规后进入 Alert 主链。</p>
 */
public final class AiReviewStatuses {

    public static final String PENDING = "PENDING";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    public static final String UNCERTAIN = "UNCERTAIN";
    public static final String ASSIGNED = "ASSIGNED";
    public static final String PROCESSING = "PROCESSING";
    public static final String CLOSED = "CLOSED";

    private static final Map<String, String> LABELS = Map.of(
            PENDING, "待复核",
            CONFIRMED, "已确认违规",
            FALSE_POSITIVE, "误报",
            UNCERTAIN, "不确定",
            ASSIGNED, "已派单",
            PROCESSING, "处理中",
            CLOSED, "已关闭");

    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private AiReviewStatuses() {
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

    /** 已进入确认后链路、计入今日确认违规指标的状态。 */
    public static boolean isConfirmedChain(String statusCode) {
        return CONFIRMED.equals(statusCode) || ASSIGNED.equals(statusCode)
                || PROCESSING.equals(statusCode) || CLOSED.equals(statusCode);
    }
}
