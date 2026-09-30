package com.bdemo.sys.ai;

import java.util.Map;

/**
 * AI 能力扩展点。
 *
 * 当前仅提供 Disabled / Rule-based 实现（零外部模型、零 API 成本、离线可运行）。
 * 未来需要接入 LLM 时，新增一个实现（如 DeepSeekAiProvider）并配置 app.ai.provider 即可，
 * 无需侵入业务/规则代码。
 */
public interface AiProvider {

    /** 是否真正启用了外部 AI 能力 */
    boolean isEnabled();

    /** 提供方名称，如 disabled / rule-based / deepseek */
    String name();

    /**
     * 在规则引擎产出的上下文上做增强（如补充自然语言解释）。
     * Disabled / Rule-based 模式原样返回；LLM 实现可在此调用模型。
     */
    Map<String, Object> enhance(Map<String, Object> context);
}
