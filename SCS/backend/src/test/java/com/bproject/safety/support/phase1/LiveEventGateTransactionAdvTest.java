package com.bproject.safety.support.phase1;

import com.bproject.safety.common.realtime.LiveEventGate;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("Step 1.5: LiveEventGate 进阶事务与隔离测试（P1-3）")
class LiveEventGateTransactionAdvTest {

    private LiveEventGate gate;
    private DataSourceTransactionManager transactionManager;
    private TransactionTemplate outerTemplate;
    private TransactionTemplate innerRequiresNewTemplate;

    @BeforeEach
    void setUp() throws Exception {
        gate = new LiveEventGate();
        DataSource dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenAnswer(inv -> mock(Connection.class));

        transactionManager = new DataSourceTransactionManager(dataSource);

        outerTemplate = new TransactionTemplate(transactionManager);
        outerTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);

        innerRequiresNewTemplate = new TransactionTemplate(transactionManager);
        innerRequiresNewTemplate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    @Test
    @DisplayName("REQUIRES_NEW: 外层提交 + 内层提交 -> 内层与外层事件均正确发布，内层提交后先行广播")
    void testRequiresNew_OuterCommit_InnerCommit() {
        List<String> published = Collections.synchronizedList(new ArrayList<>());

        outerTemplate.execute(outerStatus -> {
            gate.buffer(() -> {
                gate.emit(() -> published.add("OUTER_1"));

                // 嵌套 REQUIRES_NEW 事务
                innerRequiresNewTemplate.execute(innerStatus -> {
                    gate.buffer(() -> {
                        gate.emit(() -> published.add("INNER_1"));
                    });
                    // 内层事务尚未提交
                    assertThat(published).isEmpty();
                    return null;
                });

                // 内层事务已提交，内层事件已广播，外层事件尚未广播
                assertThat(published).containsExactly("INNER_1");

                gate.emit(() -> published.add("OUTER_2"));
            });
            return null;
        });

        // 外层事务提交后，外层事件广播完毕
        assertThat(published).containsExactly("INNER_1", "OUTER_1", "OUTER_2");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("REQUIRES_NEW: 外层提交 + 内层回滚 -> 内层事件被丢弃，外层事件正常发布")
    void testRequiresNew_OuterCommit_InnerRollback() {
        List<String> published = Collections.synchronizedList(new ArrayList<>());

        outerTemplate.execute(outerStatus -> {
            gate.buffer(() -> {
                gate.emit(() -> published.add("OUTER_1"));

                try {
                    innerRequiresNewTemplate.execute(innerStatus -> {
                        gate.buffer(() -> {
                            gate.emit(() -> published.add("INNER_FAIL"));
                        });
                        throw new RuntimeException("内层业务异常，触发回滚");
                    });
                } catch (RuntimeException ex) {
                    // 外层捕获内层异常并决定继续外层提交
                }

                gate.emit(() -> published.add("OUTER_2"));
            });
            return null;
        });

        // 内层事件被丢弃，外层事件成功发布
        assertThat(published).containsExactly("OUTER_1", "OUTER_2");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("REQUIRES_NEW: 外层回滚 + 内层提交 -> 内层事件已提交故正常发布，外层事件被彻底丢弃")
    void testRequiresNew_OuterRollback_InnerCommit() {
        List<String> published = Collections.synchronizedList(new ArrayList<>());

        try {
            outerTemplate.execute(outerStatus -> {
                gate.buffer(() -> {
                    gate.emit(() -> published.add("OUTER_1"));

                    innerRequiresNewTemplate.execute(innerStatus -> {
                        gate.buffer(() -> {
                            gate.emit(() -> published.add("INNER_SUCCESS"));
                        });
                        return null;
                    });

                    // 内层已独立提交并广播
                    assertThat(published).containsExactly("INNER_SUCCESS");

                    // 外层抛出异常触发回滚
                    throw new RuntimeException("外层业务异常，触发外层回滚");
                });
                return null;
            });
        } catch (RuntimeException ignored) {
            // 预期外层回滚
        }

        // 外层回滚后，已提交的内层事件保留，外层事件丢弃
        assertThat(published).containsExactly("INNER_SUCCESS");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("同线程连续事务：Tx1提交 -> Tx2回滚 -> Tx3提交，状态无任何串扰且零泄漏")
    void testSequentialTransactions_SameThread() {
        List<String> published = Collections.synchronizedList(new ArrayList<>());

        // Tx1: 提交
        outerTemplate.execute(status -> {
            gate.buffer(() -> gate.emit(() -> published.add("TX1")));
            return null;
        });
        assertThat(published).containsExactly("TX1");
        assertThat(gate.isBuffering()).isFalse();

        // Tx2: 回滚
        try {
            outerTemplate.execute(status -> {
                gate.buffer(() -> gate.emit(() -> published.add("TX2_SHOULD_ROLLBACK")));
                throw new RuntimeException("Tx2 异常回滚");
            });
        } catch (RuntimeException ignored) {
        }
        assertThat(published).containsExactly("TX1");
        assertThat(gate.isBuffering()).isFalse();

        // Tx3: 提交
        outerTemplate.execute(status -> {
            gate.buffer(() -> gate.emit(() -> published.add("TX3")));
            return null;
        });
        assertThat(published).containsExactly("TX1", "TX3");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("事务回滚后立即执行无事务 emit：ThreadLocal 零残留，无事务事件即刻发出")
    void testRollbackFollowedImmediatelyByNoTransactionEmit() {
        AtomicBoolean txEventFired = new AtomicBoolean(false);
        AtomicBoolean noTxEventFired = new AtomicBoolean(false);

        try {
            outerTemplate.execute(status -> {
                gate.buffer(() -> gate.emit(() -> txEventFired.set(true)));
                throw new RuntimeException("强制回滚");
            });
        } catch (RuntimeException ignored) {
        }

        assertThat(txEventFired.get()).isFalse();
        assertThat(gate.isBuffering()).isFalse();

        // 紧接着在同一线程无事务环境下直接 emit
        gate.emit(() -> noTxEventFired.set(true));
        assertThat(noTxEventFired.get()).isTrue();

        // 在同一线程无事务环境下调用 buffer
        AtomicInteger bufferCount = new AtomicInteger(0);
        gate.buffer(() -> {
            gate.emit(bufferCount::incrementAndGet);
            assertThat(bufferCount.get()).isEqualTo(0);
        });
        assertThat(bufferCount.get()).isEqualTo(1);
        assertThat(gate.isBuffering()).isFalse();
    }
}
