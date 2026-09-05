package com.bproject.safety.module.ai.model;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * AI 检测框（坐标均为相对截图的百分比 0~100）。
 *
 * @param tone person=人物框 / violation=违规标记 / zone=危险区域边界
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AiBox(String id, String label, Double score, double x, double y, double w, double h, String tone) {
}
