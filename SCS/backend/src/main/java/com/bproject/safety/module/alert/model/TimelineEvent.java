package com.bproject.safety.module.alert.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 告警时间线节点。
 *
 * <p>展示字段 {@code time/text/state} 与前端 TimelineNode 对齐（time 为 HH:mm:ss 文本）；
 * {@code at} 为规范 ISO-8601 时间戳。落库前语义收口 F-12 新增两个机器字段：</p>
 * <ul>
 *   <li>{@code eventType}：机器事件类型（见 {@link AlertTimelineEventTypes}），供统计 / 时长计算，
 *       不再依赖中文 text 关键字匹配；</li>
 *   <li>{@code sequenceNo}：同一告警内严格递增的序号，不依赖 Java List 的偶然顺序。</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineEvent(String time, OffsetDateTime at, String text, String state,
                            String eventType, Integer sequenceNo) {

    /** 兼容旧的 4 参构造（无机器事件类型 / 序号），主要供历史 Seed 展示节点使用。 */
    public TimelineEvent(String time, OffsetDateTime at, String text, String state) {
        this(time, at, text, state, null, null);
    }

    public static TimelineEvent done(String time, OffsetDateTime at, String text) {
        return new TimelineEvent(time, at, text, "done", null, null);
    }

    public static TimelineEvent active(String time, OffsetDateTime at, String text) {
        return new TimelineEvent(time, at, text, "active", null, null);
    }

    public static TimelineEvent of(String time, OffsetDateTime at, String text, String state,
                                   String eventType, Integer sequenceNo) {
        return new TimelineEvent(time, at, text, state, eventType, sequenceNo);
    }

    /** 携带机器事件类型与序号的节点（state 默认按 eventType 推导，也可显式指定）。 */
    public static TimelineEvent event(OffsetDateTime at, String hms, String text, String state,
                                      String eventType, Integer sequenceNo) {
        return new TimelineEvent(hms, at, text, state, eventType, sequenceNo);
    }
}
