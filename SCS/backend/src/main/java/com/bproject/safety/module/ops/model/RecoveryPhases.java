package com.bproject.safety.module.ops.model;

import java.util.ArrayList;
import java.util.List;

/** 云边恢复阶段定义（固定顺序，不允许跳步）。 */
public final class RecoveryPhases {

    public static final String CONNECTIVITY = "CONNECTIVITY";
    public static final String CLOCK = "CLOCK_RECONCILIATION";
    public static final String RULE = "RULE_RECONCILIATION";
    public static final String EVENT_REPLAY = "EVENT_REPLAY";
    public static final String FINAL_CHECK = "FINAL_CHECK";
    /** 终态：节点恢复 ONLINE。 */
    public static final String ONLINE = "ONLINE";

    /** 恢复阶段顺序（不含终态 ONLINE）。 */
    public static final List<String> ORDER = List.of(
            CONNECTIVITY, CLOCK, RULE, EVENT_REPLAY, FINAL_CHECK);

    public static final List<String> ALL = List.of(
            CONNECTIVITY, CLOCK, RULE, EVENT_REPLAY, FINAL_CHECK, ONLINE);

    public static String label(String key) {
        return switch (key) {
            case CONNECTIVITY -> "恢复连接";
            case CLOCK -> "时间对账";
            case RULE -> "规则版本对账";
            case EVENT_REPLAY -> "缓存事件补传";
            case FINAL_CHECK -> "最终检查";
            case ONLINE -> "恢复在线";
            default -> key;
        };
    }

    private RecoveryPhases() {
    }

    /** 初始化一份完整的恢复时间线（全部 WAIT，可变，供阶段推进时追加 ONLINE 终态）。 */
    public static List<RecoveryPhase> newTimeline() {
        return new ArrayList<>(ORDER.stream().map(k -> new RecoveryPhase(k, label(k))).toList());
    }
}
