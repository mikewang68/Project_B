package com.bproject.safety.module.rule.service;

/**
 * 规则业务编号生成器（Phase B 抽象）。
 *
 * <p>把 {@code RULE-<域前缀>-NNN} 编号生成从 RuleService 抽离；当前 Demo 实现基于单仓储计数，
 * <b>不保证多实例唯一</b>；openGauss 阶段可替换为序列实现。</p>
 */
public interface RuleNumberGenerator {

    /** 按规则分类生成下一个规则业务编号（格式如 RULE-PER-023）。 */
    String nextRuleNumber(String category);
}
