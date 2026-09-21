package com.bproject.safety.module.rule.repository;

/**
 * 规则列表筛选条件（均为可选，空字符串 / null 表示不过滤）。
 *
 * <p>Phase B：从 RuleRepository 接口内部移出为独立查询模型（与 AlertQuery / AiEventQuery 惯例一致），
 * Controller / Service 不再 import Repository 嵌套类型。</p>
 */
public record RuleQuery(String keyword, String category, String status, String risk) {
}
