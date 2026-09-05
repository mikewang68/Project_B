package com.bproject.safety.module.rule.repository;

import com.bproject.safety.module.rule.model.DemoRule;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 进程内规则存储（Backend Demo 专用），查询统一返回副本。 */
@Repository
public class InMemoryRuleRepository implements RuleRepository {

    private final ConcurrentHashMap<String, DemoRule> store = new ConcurrentHashMap<>();

    private static final Comparator<DemoRule> ORDER = Comparator
            .comparing((DemoRule r) -> r.updatedAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(r -> r.id, Comparator.nullsLast(Comparator.naturalOrder()));

    @Override
    public List<DemoRule> findAll() {
        return store.values().stream().map(DemoRule::copy).sorted(ORDER).toList();
    }

    @Override
    public List<DemoRule> filter(RuleQuery q) {
        return store.values().stream().map(DemoRule::copy)
                .filter(r -> q.category() == null || q.category().isBlank() || q.category().equals(r.category))
                .filter(r -> q.status() == null || q.status().isBlank() || q.status().equals(r.status))
                .filter(r -> q.risk() == null || q.risk().isBlank() || q.risk().equals(r.risk))
                .filter(r -> matchKeyword(q.keyword(), r))
                .sorted(ORDER)
                .toList();
    }

    @Override
    public Optional<DemoRule> findById(String id) {
        DemoRule r = store.get(id);
        return r == null ? Optional.empty() : Optional.of(r.copy());
    }

    @Override
    public DemoRule save(DemoRule rule) {
        store.put(rule.id, rule);
        return rule.copy();
    }

    @Override
    public long count() {
        return store.size();
    }

    /** 清空存储（测试 / 重新播种用）。 */
    public void clear() {
        store.clear();
    }

    private boolean matchKeyword(String kw, DemoRule r) {
        if (kw == null || kw.isBlank()) {
            return true;
        }
        return contains(r.id, kw) || contains(r.name, kw) || contains(r.owner, kw);
    }

    private boolean contains(String v, String kw) {
        return v != null && v.contains(kw);
    }
}
