package com.bproject.safety.module.rule.repository;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** 非服务器 profile：内存实现（本地开发/测试）。 */
@Component
@Profile("!server")
public class InMemoryRuleConflictIgnoreStore implements RuleConflictIgnoreStore {

    private final Map<String, IgnoredConflict> store = new ConcurrentHashMap<>();

    private static String key(String ruleA, String ruleB, String param) {
        return ruleA + "|" + ruleB + "|" + param;
    }

    @Override
    public boolean ignore(String ruleA, String ruleB, String param, String operator) {
        return store.putIfAbsent(key(ruleA, ruleB, param),
                new IgnoredConflict(ruleA, ruleB, param, operator, null)) == null;
    }

    @Override
    public List<IgnoredConflict> list() {
        return List.copyOf(store.values());
    }

    @Override
    public boolean isIgnored(String ruleA, String ruleB, String param) {
        return store.containsKey(key(ruleA, ruleB, param));
    }
}
