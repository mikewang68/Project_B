package com.bproject.safety.module.ai.model;

/** AI 复核状态（Demo 阶段沿用前端中文枚举，不在本阶段重构正式状态机）。 */
public final class AiReviewStatuses {

    private AiReviewStatuses() {
    }

    public static final String PENDING = "待复核";
    public static final String CONFIRMED = "已确认违规";
    public static final String FALSE_POSITIVE = "误报";
    public static final String UNCERTAIN = "不确定";
    public static final String ASSIGNED = "已派单";
    public static final String PROCESSING = "处理中";
    public static final String CLOSED = "已关闭";

    /** 已进入确认后链路、计入今日确认违规指标的状态。 */
    public static boolean isConfirmedChain(String status) {
        return CONFIRMED.equals(status) || ASSIGNED.equals(status)
                || PROCESSING.equals(status) || CLOSED.equals(status);
    }
}
