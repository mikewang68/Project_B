package com.bproject.safety.module.ai.model;

/**
 * AI 事件时间线机器事件类型（落库前语义收口 F-12）。
 *
 * <p>仅用于 AI 复核域，与 Alert 的 {@code AlertTimelineEventTypes} 保持独立，
 * 不强行合并为一张通用事件表。</p>
 */
public final class AiTimelineEventTypes {

    /** AI 检测到疑似事件。 */
    public static final String DETECTED = "DETECTED";
    /** 进入人工复核队列。 */
    public static final String QUEUED = "QUEUED";
    /** 人工确认违规。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 标记误报。 */
    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";
    /** 暂不确定，转人工复核。 */
    public static final String UNCERTAIN = "UNCERTAIN";
    /** 已派单。 */
    public static final String ASSIGNED = "ASSIGNED";
    /** 开始现场处置。 */
    public static final String PROCESSING = "PROCESSING";
    /** AI 事件处置完成关闭。 */
    public static final String CLOSED = "CLOSED";
    /** 已关联生成安全告警。 */
    public static final String ALERT_LINKED = "ALERT_LINKED";
    /** 一般备注节点。 */
    public static final String NOTE = "NOTE";

    private AiTimelineEventTypes() {
    }
}
