package com.bproject.safety.support.phase1;

import com.bproject.safety.common.realtime.LiveEventGate;
import java.sql.Connection;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Step 1: LiveEventGate 事务边界测试（afterCommit 提交触发、rollback 丢弃、无事务立即执行）")
class LiveEventGateTransactionTest {

    private LiveEventGate gate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate transactionTemplate;

    @BeforeEach
    void setUp() throws Exception {
        gate = new LiveEventGate();
        DataSource dataSource = mock(DataSource.class);
        Connection connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);

        transactionManager = new DataSourceTransactionManager(dataSource);
        transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Test
    @DisplayName("事务提交：事件缓冲在事务中，只有在 afterCommit 时才真正触发广播")
    void testTransactionCommitTriggersAfterCommit() {
        AtomicInteger eventCounter = new AtomicInteger(0);

        transactionTemplate.execute(status -> {
            gate.buffer(() -> {
                gate.emit(eventCounter::incrementAndGet);
            });
            // 事务尚未提交，此时不应该触发广播
            assertThat(eventCounter.get()).isEqualTo(0);
            return null;
        });

        // 事务提交后，afterCommit 触发执行
        assertThat(eventCounter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("事务回滚：事务内异常回滚时，缓冲的事件被彻底丢弃，绝不触发广播")
    void testTransactionRollbackDiscardsEvents() {
        AtomicBoolean eventFired = new AtomicBoolean(false);

        try {
            transactionTemplate.execute(status -> {
                gate.buffer(() -> {
                    gate.emit(() -> eventFired.set(true));
                });
                // 抛出异常触发回滚
                throw new RuntimeException("Simulated business error causing transaction rollback");
            });
        } catch (RuntimeException ignored) {
            // Expected
        }

        // 事务回滚后，事件不得触发
        assertThat(eventFired.get()).isFalse();
    }

    @Test
    @DisplayName("无事务环境：buffer 内代码执行完毕后立即 flush 触发广播，保持非事务兼容性")
    void testNoTransactionImmediateExecution() {
        AtomicInteger counter = new AtomicInteger(0);

        gate.buffer(() -> {
            gate.emit(counter::incrementAndGet);
            // 尚未退出 buffer，已入队但未 flush
            assertThat(counter.get()).isEqualTo(0);
        });

        // 退出 buffer 后立即执行
        assertThat(counter.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("未开启 buffer 且无事务：emit 立即执行")
    void testEmitWithoutBuffer() {
        AtomicInteger counter = new AtomicInteger(0);
        gate.emit(counter::incrementAndGet);
        assertThat(counter.get()).isEqualTo(1);
    }
}
