package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.realtime.LiveEventGate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B.5 B5-03: LiveEventGate 嵌套可重入性与异常隔离测试。
 * 验证：
 * 1. 单层 buffer 保持原行为；
 * 2. 外层 A、内层 B、外层 C：最终保序 [A, B, C]，且仅在最外层执行完毕后统一发布；
 * 3. 内层抛异常：整个根上下文丢弃全部事件，A/B/C 均不发送；
 * 4. 内层抛异常即使被外层捕获（catch），根上下文依然失败，最外层不 flush 任何事件；
 * 5. 三层嵌套调用：只在最外层退出时统一 flush 一次；
 * 6. 某个单条广播抛异常，不影响后续事件继续发送。
 */
class LiveEventGateReentrancyTest {

    private LiveEventGate gate;
    private List<String> emitted;

    @BeforeEach
    void setUp() {
        gate = new LiveEventGate();
        emitted = new ArrayList<>();
    }

    @Test
    @DisplayName("Test 1: 单层 buffer 保持原行为，在 action 成功后统一发出")
    void singleLevelBuffer() {
        gate.buffer(() -> {
            gate.emit(() -> emitted.add("evt-1"));
            gate.emit(() -> emitted.add("evt-2"));
            assertThat(emitted).as("执行中不应立即发出").isEmpty();
        });
        assertThat(emitted).containsExactly("evt-1", "evt-2");
    }

    @Test
    @DisplayName("Test 2: 嵌套可重入：外层 A、内层 B、外层 C 顺序保序且最外层结束后统一发出")
    void nestedBufferOrdering() {
        gate.buffer(() -> {
            gate.emit(() -> emitted.add("evt-A"));
            assertThat(emitted).isEmpty();

            // 嵌套内层
            gate.buffer(() -> {
                gate.emit(() -> emitted.add("evt-B"));
                assertThat(emitted).as("内层成功时不应提前 flush").isEmpty();
            });

            assertThat(emitted).as("内层退出后仍在外层缓冲中").isEmpty();
            gate.emit(() -> emitted.add("evt-C"));
        });

        assertThat(emitted)
                .as("最外层退出后按入队顺序统一执行")
                .containsExactly("evt-A", "evt-B", "evt-C");
    }

    @Test
    @DisplayName("Test 3: 内层抛异常：根上下文失败并丢弃全部事件，A/B/C 均不发送")
    void innerFailureDiscardsAll() {
        assertThatThrownBy(() -> gate.buffer(() -> {
            gate.emit(() -> emitted.add("evt-A"));
            gate.buffer(() -> {
                gate.emit(() -> emitted.add("evt-B"));
                throw new IllegalStateException("内层事务失败");
            });
            gate.emit(() -> emitted.add("evt-C"));
        })).isInstanceOf(IllegalStateException.class);

        assertThat(emitted).as("内层失败导致整个事务丢弃全部事件").isEmpty();
    }

    @Test
    @DisplayName("Test 4: 内层抛异常即使被外层捕获（catch），根上下文依然标记失败，不 flush 任何事件")
    void innerFailureCaughtByOuterStillDiscards() {
        gate.buffer(() -> {
            gate.emit(() -> emitted.add("evt-A"));

            try {
                gate.buffer(() -> {
                    gate.emit(() -> emitted.add("evt-B"));
                    throw new RuntimeException("内层失败但被外层捕获");
                });
            } catch (RuntimeException ex) {
                // 外层显式 catch 了异常
            }

            // 外层继续尝试发送 C
            gate.emit(() -> emitted.add("evt-C"));
        });

        assertThat(emitted)
                .as("根上下文已因内层失败被污染，最外层不得错误 flush 任何事件")
                .isEmpty();
    }

    @Test
    @DisplayName("Test 5: 三层嵌套：只在最外层 flush 一次")
    void nestedThreeLevelsFlushesOnce() {
        gate.buffer(() -> {
            gate.emit(() -> emitted.add("level-1"));
            gate.buffer(() -> {
                gate.emit(() -> emitted.add("level-2"));
                gate.buffer(() -> {
                    gate.emit(() -> emitted.add("level-3"));
                    assertThat(emitted).isEmpty();
                });
                assertThat(emitted).isEmpty();
            });
            assertThat(emitted).isEmpty();
        });

        assertThat(emitted).containsExactly("level-1", "level-2", "level-3");
    }

    @Test
    @DisplayName("Test 6: 某个单条广播失败，不阻断队列中其他事件发送")
    void notifierSingleFailureDoesNotBlockRemaining() {
        gate.buffer(() -> {
            gate.emit(() -> emitted.add("first"));
            gate.emit(() -> {
                throw new RuntimeException("广播单条故障");
            });
            gate.emit(() -> emitted.add("third"));
        });

        assertThat(emitted).containsExactly("first", "third");
    }
}
