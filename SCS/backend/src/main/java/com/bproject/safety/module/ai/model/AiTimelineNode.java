package com.bproject.safety.module.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI 事件时间线节点（与前端 AiTimelineNode 对齐）。
 *
 * @param state      done=已完成 / active=当前节点 / pending=待发生
 * @param eventType  机器事件类型（见 {@link AiTimelineEventTypes}），落库前语义收口 F-12；可为 null（纯展示节点）
 * @param sequenceNo 同一 AI 事件内严格递增序号，不依赖 List 偶然顺序
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiTimelineNode(String time, String text, String state, String eventType, Integer sequenceNo) {

    /** 兼容旧 3 参构造（纯展示节点，无机器事件类型）。 */
    public AiTimelineNode(String time, String text, String state) {
        this(time, text, state, null, null);
    }

    public static AiTimelineNode of(String time, String text, String state, String eventType, Integer sequenceNo) {
        return new AiTimelineNode(time, text, state, eventType, sequenceNo);
    }
}
