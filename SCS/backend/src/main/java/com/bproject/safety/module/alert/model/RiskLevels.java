package com.bproject.safety.module.alert.model;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 告警风险等级机器 Code（落库前语义收口 F-01）。
 *
 * <p>内部业务判断 / 存储 / 未来数据库列一律使用稳定机器 code；中文仅为展示标签，
 * 由 {@link #label(String)} 派生，不得再作为业务判断键。</p>
 *
 * <ul>
 *   <li>{@link #NORMAL} 一般</li>
 *   <li>{@link #WARNING} 预警</li>
 *   <li>{@link #SEVERE} 严重</li>
 *   <li>{@link #URGENT} 紧急</li>
 * </ul>
 */
public final class RiskLevels {

    public static final String NORMAL = "NORMAL";
    public static final String WARNING = "WARNING";
    public static final String SEVERE = "SEVERE";
    public static final String URGENT = "URGENT";

    /** 风险由低到高的稳定顺序。 */
    public static final List<String> ORDERED = List.of(NORMAL, WARNING, SEVERE, URGENT);

    private static final Map<String, String> LABELS = Map.of(
            NORMAL, "一般",
            WARNING, "预警",
            SEVERE, "严重",
            URGENT, "紧急");

    /** 旧中文标签 → code（仅供旧客户端只传中文等级时的 Deprecated 兼容路径）。 */
    private static final Map<String, String> BY_LABEL = LABELS.entrySet().stream()
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getValue, Map.Entry::getKey));

    private RiskLevels() {
    }

    /** 机器 code → 中文展示标签；未知 code 返回 null（调用方可据此展示“未知”并告警）。 */
    public static String label(String code) {
        return code == null ? null : LABELS.get(code);
    }

    /** 中文标签 → 机器 code；未知返回 null。 */
    public static String fromLabel(String label) {
        return label == null ? null : BY_LABEL.get(label);
    }

    /** 兼容旧入参：传 code 原样返回，传中文标签转 code；都不匹配返回 null。 */
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

    /** 严重及以上（需要联动 / 强制复核）。 */
    public static boolean isHigh(String code) {
        return SEVERE.equals(code) || URGENT.equals(code);
    }

    /** 等级权重（越大越严重），未知返回 -1。 */
    public static int rank(String code) {
        return ORDERED.indexOf(code);
    }

    public static Optional<String> higher(String a, String b) {
        int ra = rank(a);
        int rb = rank(b);
        if (ra < 0 || rb < 0) {
            return Optional.empty();
        }
        return Optional.of(rb > ra ? b : a);
    }

    /** 供 label mapper 枚举遍历。 */
    public static List<Map.Entry<String, String>> entries() {
        return ORDERED.stream().map(c -> Map.entry(c, LABELS.get(c))).toList();
    }

    static Function<String, String> labelFn() {
        return RiskLevels::label;
    }
}
