package com.bproject.safety.module.ai.service;

/**
 * AI 事件业务编号生成器（Phase B 抽象）。
 *
 * <p>把 {@code AI-E-yyyyMMdd-NNN} 编号生成从 AiEventService 抽离；当前 Demo 实现基于单仓储计数，
 * <b>不保证多实例唯一</b>；openGauss 阶段可替换为数据库日序列 / 号段实现。</p>
 */
public interface AiEventNumberGenerator {

    /** 生成下一个 AI 事件业务编号（格式如 AI-E-20260920-001）。 */
    String nextAiEventNumber();
}
