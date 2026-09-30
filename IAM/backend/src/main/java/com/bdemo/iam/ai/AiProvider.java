package com.bdemo.iam.ai;

/**
 * AI 能力扩展点。
 *
 * 当前系统的"智能"由确定性规则引擎提供，不依赖本接口。
 * 未来新增 DeepSeekAiProvider 实现本接口，可对规则产出的结构化结果做自然语言增强，
 * 规则引擎与业务代码无需改动（开闭原则）。
 */
public interface AiProvider {

    /** 是否启用真实 AI 能力。规则/禁用模式返回 false。 */
    boolean isEnabled();

    /** Provider 名称，如 disabled / deepseek。 */
    String name();

    /** 对结构化上下文做增强；禁用模式下原样返回。 */
    String enhance(String context);
}
