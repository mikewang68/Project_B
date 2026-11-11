package com.bproject.safety.module.rule.repository;

import java.util.List;

/** 规则冲突忽略决策存储（conflict-check 排除已忽略配对）。 */
public interface RuleConflictIgnoreStore {

    record IgnoredConflict(String ruleA, String ruleB, String param, String ignoredBy, String ignoredAt) {
    }

    /** 幂等记录忽略决策；重复忽略同一配对返回 false。 */
    boolean ignore(String ruleA, String ruleB, String param, String operator);

    List<IgnoredConflict> list();

    boolean isIgnored(String ruleA, String ruleB, String param);
}
