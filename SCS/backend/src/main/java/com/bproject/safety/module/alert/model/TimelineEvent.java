package com.bproject.safety.module.alert.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.OffsetDateTime;

/**
 * 告警时间线节点。
 *
 * <p>{@code time/text/state} 与前端 TimelineNode 完全对齐（time 为 HH:mm:ss 展示文本，
 * 保持移除 Mock 后视觉不变）；{@code at} 为规范 ISO-8601 时间戳，供后续联调使用。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TimelineEvent(String time, OffsetDateTime at, String text, String state) {

    public static TimelineEvent done(String time, OffsetDateTime at, String text) {
        return new TimelineEvent(time, at, text, "done");
    }

    public static TimelineEvent active(String time, OffsetDateTime at, String text) {
        return new TimelineEvent(time, at, text, "active");
    }
}
