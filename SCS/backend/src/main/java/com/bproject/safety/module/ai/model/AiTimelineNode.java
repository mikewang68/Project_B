package com.bproject.safety.module.ai.model;

/**
 * AI 事件时间线节点（与前端 AiTimelineNode 对齐）。
 *
 * @param state done=已完成 / active=当前节点 / pending=待发生
 */
public record AiTimelineNode(String time, String text, String state) {
}
