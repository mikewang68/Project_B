package com.bproject.safety.support.number;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 基于 openGauss 计数表 {@code safety.sys_business_number} 的高性能原子序列发号存储组件。
 *
 * <p>核心并发保障与 O(1) 性能特征：
 * <ul>
 *   <li>常规发号（行记录已存在）：单条 {@code UPDATE ... RETURNING} 原语原子获取排他锁并递增，SQL 成本 O(1)，无跨表扫描。</li>
 *   <li>日切首发（行记录不存在）：先执行 {@code INSERT (1)}；若遭遇并发插入导致主键冲突，自动在新的独立事务中重试 {@code UPDATE ... RETURNING}。</li>
 *   <li>事务隔离与回滚语义：使用独立事务 {@link TransactionDefinition#PROPAGATION_REQUIRES_NEW}，发号即刻提交释放行锁，
 *       遵循 {@code NUMBER_ROLLBACK_SEMANTICS: GAP_ALLOWED}，彻底杜绝多 JVM / 多实例间读未提交与重号风险。</li>
 * </ul>
 *
 * <p>本组件属于底层基础设施/技术发号组件，不计入 9 个业务 Repository 迁移统计口径。</p>
 */
@Component
@Profile("server")
public class JdbcBusinessNumberStore {

    private static final Logger log = LoggerFactory.getLogger(JdbcBusinessNumberStore.class);

    private static final String SQL_INCREMENT_RETURNING = """
            UPDATE safety.sys_business_number
            SET current_value = current_value + 1, lock_version = lock_version + 1, updated_at = now()
            WHERE number_type = :type AND business_date = :date
            RETURNING current_value
            """;

    private static final String SQL_INSERT_INITIAL = """
            INSERT INTO safety.sys_business_number (number_type, business_date, current_value, lock_version, updated_at)
            VALUES (:type, :date, 1, 0, now())
            """;

    private static final String SQL_QUERY_CURRENT = """
            SELECT current_value FROM safety.sys_business_number
            WHERE number_type = :type AND business_date = :date
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;
    private final TransactionTemplate requiresNewTemplate;

    public JdbcBusinessNumberStore(NamedParameterJdbcTemplate jdbcTemplate,
                                  PlatformTransactionManager transactionManager) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        Objects.requireNonNull(transactionManager, "transactionManager must not be null");
        this.requiresNewTemplate = new TransactionTemplate(transactionManager);
        this.requiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * 为指定的业务类型和业务日期原子申请下一个序列号。
     *
     * @param numberType   业务类型代码（如 ALERT, AI_EVENT, RULE, FENCE 等）
     * @param businessDate 业务日期（如 yyyyMMdd 或 GLOBAL）
     * @return 严格递增且唯一的序列号（>= 1）
     */
    public long nextSequence(String numberType, String businessDate) {
        Objects.requireNonNull(numberType, "numberType must not be null");
        Objects.requireNonNull(businessDate, "businessDate must not be null");

        // 最多重试 3 次处理跨 JVM 首日插入竞争
        for (int attempt = 1; attempt <= 3; attempt++) {
            try {
                Long val = requiresNewTemplate.execute(status -> doNextSequence(numberType, businessDate));
                if (val != null) {
                    return val;
                }
            } catch (DataIntegrityViolationException ex) {
                log.debug("首发并发插入冲突 (attempt {}/3)，即刻在独立新事务中重试 UPDATE ... RETURNING：{}:{}",
                        attempt, numberType, businessDate);
            }
        }
        throw new IllegalStateException("超过最大重试次数，未能成功申请业务序号: " + numberType + ":" + businessDate);
    }

    private Long doNextSequence(String numberType, String businessDate) {
        Map<String, Object> params = Map.of("type", numberType, "date", businessDate);

        // 1. 常规路径：尝试原子递增已有行
        List<Long> updated = jdbcTemplate.query(SQL_INCREMENT_RETURNING, params, (rs, rowNum) -> rs.getLong(1));
        if (!updated.isEmpty()) {
            return updated.get(0);
        }

        // 2. 初始路径：当日尚未建立计数行，尝试插入初值 1
        jdbcTemplate.update(SQL_INSERT_INITIAL, params);
        return 1L;
    }

    /**
     * 查询指定业务维度当前的最大序列值（不产生发号动作）。
     */
    public Optional<Long> getCurrentValue(String numberType, String businessDate) {
        Map<String, Object> params = Map.of("type", numberType, "date", businessDate);
        List<Long> list = jdbcTemplate.query(SQL_QUERY_CURRENT, params, (rs, rowNum) -> rs.getLong(1));
        return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
    }
}
