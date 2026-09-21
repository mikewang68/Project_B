package com.bproject.safety.support;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

/**
 * 测试环境（Profile = test）下的事务管理器配置。
 *
 * <p>背景：application-test.yml 中的 DataSource 指向本地占位拒绝端口（127.0.0.1:1），
 * 用于在无真实 openGauss 的本地与 CI 环境中快速失败。在此环境下运行 SpringBootTest 时，
 * 为 InMemory 仓储上的 @Transactional 方法提供轻量级事务管理支持，完整驱动
 * TransactionSynchronization（含 afterCommit 触发与回滚隔离），避免因无数据库而抛出连接拒绝异常。</p>
 */
@Configuration
@Profile("test")
public class TestTransactionConfig {

    @Bean
    @Primary
    public PlatformTransactionManager testTransactionManager() {
        AbstractPlatformTransactionManager tm = new AbstractPlatformTransactionManager() {
            @Override
            protected Object doGetTransaction() {
                return new Object();
            }

            @Override
            protected void doBegin(Object transaction, TransactionDefinition definition) {
            }

            @Override
            protected void doCommit(DefaultTransactionStatus status) {
            }

            @Override
            protected void doRollback(DefaultTransactionStatus status) {
            }
        };
        tm.setTransactionSynchronization(AbstractPlatformTransactionManager.SYNCHRONIZATION_ALWAYS);
        return tm;
    }
}
