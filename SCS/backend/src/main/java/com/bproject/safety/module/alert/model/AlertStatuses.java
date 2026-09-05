package com.bproject.safety.module.alert.model;

import java.util.Set;

/**
 * 告警状态常量（Backend Demo 阶段直接沿用前端中文状态词，不做最终状态机设计）。
 *
 * <p>演示主链路：待确认 → 待派单 → 待处理 → 处理中 → 待复核 → 已关闭。</p>
 */
public final class AlertStatuses {

    public static final String PENDING_CONFIRM = "待确认";
    public static final String CONFIRMED = "已确认";
    public static final String TO_ASSIGN = "待派单";
    public static final String TO_HANDLE = "待处理";
    public static final String HANDLING = "处理中";
    public static final String TO_REVIEW = "待复核";
    public static final String CLOSED = "已关闭";
    public static final String ESCALATED = "已升级";

    /** 顶部指标 active 口径，与前端 ACTIVE_STATUSES 保持一致。 */
    public static final Set<String> ACTIVE = Set.of(CONFIRMED, TO_ASSIGN, TO_HANDLE, HANDLING, TO_REVIEW, ESCALATED);

    private AlertStatuses() {
    }
}
