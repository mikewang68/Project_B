package com.bproject.safety.module.alert.model;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 告警状态机器 Code（落库前语义收口 F-01）。
 *
 * <p>内部状态机判断 / 存储使用稳定机器 code；中文仅为展示标签，由 {@link #label(String)} 派生。</p>
 *
 * <p>演示主链路：PENDING_CONFIRM → PENDING_ASSIGNMENT → PENDING_PROCESS → PROCESSING
 * → PENDING_REVIEW → CLOSED。</p>
 */
public final class AlertStatuses {

    public static final String PENDING_CONFIRM = "PENDING_CONFIRM";
    public static final String CONFIRMED = "CONFIRMED";
    public static final String PENDING_ASSIGNMENT = "PENDING_ASSIGNMENT";
    public static final String PENDING_PROCESS = "PENDING_PROCESS";
    public static final String PROCESSING = "PROCESSING";
    public static final String PENDING_REVIEW = "PENDING_REVIEW";
    public static final String CLOSED = "CLOSED";
    /**
     * @deprecated Phase B.5 (B5-01): 事件升级为风险等级变化，不再作为主生命周期状态；
     * 仅保留用于历史展示与反向映射向后兼容。
     */
    @Deprecated
    public static final String ESCALATED = "ESCALATED";

    /** 顶部指标 active 口径，与前端 ACTIVE_STATUSES 保持一致。 */
    public static final Set<String> ACTIVE = Set.of(
            CONFIRMED, PENDING_ASSIGNMENT, PENDING_PROCESS, PROCESSING, PENDING_REVIEW, ESCALATED);

    private static final Map<String, String> LABELS = Map.ofEntries(
            Map.entry(PENDING_CONFIRM, "待确认"),
            Map.entry(CONFIRMED, "已确认"),
            Map.entry(PENDING_ASSIGNMENT, "待派单"),
            Map.entry(PENDING_PROCESS, "待处理"),
            Map.entry(PROCESSING, "处理中"),
            Map.entry(PENDING_REVIEW, "待复核"),
            Map.entry(CLOSED, "已关闭"),
            Map.entry(ESCALATED, "已升级"));

    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private AlertStatuses() {
    }

    /** 机器 code → 中文展示标签。 */
    public static String label(String code) {
        return code == null ? null : LABELS.get(code);
    }

    /** 中文标签 → code；未知返回 null。 */
    public static String fromLabel(String label) {
        return label == null ? null : BY_LABEL.get(label);
    }

    /** 传 code 原样返回，传中文标签转 code；都不匹配返回 null。 */
    public static String normalize(String codeOrLabel) {
        if (codeOrLabel == null || codeOrLabel.isBlank()) {
            return null;
        }
        if (LABELS.containsKey(codeOrLabel)) {
            return codeOrLabel;
        }
        return BY_LABEL.get(codeOrLabel);
    }

    public static boolean isValid(String code) {
        return code != null && LABELS.containsKey(code);
    }
}
