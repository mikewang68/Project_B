package com.bproject.safety.module.rule.repository;

import com.bproject.safety.module.rule.model.DemoRule;
import java.util.List;
import java.util.Optional;

/**
 * 规则存储抽象；本地实现为 {@link InMemoryRuleRepository}，服务器阶段可替换 openGauss 实现。
 *
 * <p>Phase B：find/save 均为 detached 副本；清空仅通过 {@code DemoClearableStore}
 * 供 Demo / Test 使用。{@link #save(DemoRule)} 保存完整规则聚合快照（含版本历史 / 边缘同步态），
 * 未来 JDBC 实现负责映射主表 / 版本表 / 同步表，Service 不感知子表。</p>
 */
public interface RuleRepository {

    List<DemoRule> findAll();

    List<DemoRule> filter(RuleQuery query);

    Optional<DemoRule> findById(String id);

    /** 新增或整体更新（规则聚合快照）。 */
    DemoRule save(DemoRule rule);

    long count();
}
