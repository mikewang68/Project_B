package com.bproject.safety.module.ai.model;

import java.util.List;

/**
 * AI 事件分页结果（Phase B：从 Repository 接口内部移出的中立查询模型）。
 *
 * <p>Controller / Service 不得为了返回分页结果而 import Repository 类型；
 * JSON 结构保持 {@code {page,pageSize,total,list}} 不变。</p>
 */
public record AiPageResult(int page, int pageSize, long total, List<DemoAiEvent> list) {
}
