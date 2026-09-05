package com.bproject.safety.module.rule.repository;

import com.bproject.safety.module.rule.model.DemoRule;
import java.util.List;
import java.util.Optional;

/** 规则存储抽象；本地实现为 {@link InMemoryRuleRepository}，服务器阶段可替换 openGauss 实现。 */
public interface RuleRepository {

    List<DemoRule> findAll();

    List<DemoRule> filter(RuleQuery query);

    Optional<DemoRule> findById(String id);

    DemoRule save(DemoRule rule);

    long count();

    /** 规则列表筛选条件（均为可选，空字符串 / null 表示不过滤）。 */
    record RuleQuery(String keyword, String category, String status, String risk) {
    }
}
