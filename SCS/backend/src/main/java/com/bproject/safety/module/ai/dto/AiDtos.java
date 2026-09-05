package com.bproject.safety.module.ai.dto;

import com.bproject.safety.module.ai.model.DemoAiEvent;
import java.util.List;

/** AI 模块对外 DTO。 */
public final class AiDtos {

    private AiDtos() {
    }

    /** 顶部指标（历史归档基数 + 当前库实时计算，口径对齐前端 AI_METRIC_BASE）。 */
    public record AiMetrics(int today, int pending, int confirmed, int falsePositive, int cameraFault) {
    }

    /** 筛选项（区域 / 摄像头下拉由当前数据派生）。 */
    public record AiFacets(List<String> areas, List<String> cameras) {
    }

    /** 列表响应：分页 + 指标 + 筛选项一次返回，减少前端请求数。 */
    public record AiEventPage(int page, int pageSize, long total, AiMetrics metrics,
                              AiFacets facets, List<DemoAiEvent> list) {
    }
}
