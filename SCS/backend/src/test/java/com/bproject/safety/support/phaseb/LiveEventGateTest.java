package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bproject.safety.common.realtime.LiveEventGate;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B：LiveEventGate 单元测试。
 * 验证“先全部 save、后统一发布”的缓冲语义、失败 discard 语义与未缓冲时的立即执行语义。
 */
class LiveEventGateTest {

    @Test
    @DisplayName("未缓冲时 emit 立即执行")
    void emitRunsImmediatelyWhenNotBuffering() {
        LiveEventGate gate = new LiveEventGate();
        List<String> ran = new ArrayList<>();
        gate.emit(() -> ran.add("a"));
        assertThat(ran).containsExactly("a");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("buffer 区间内 emit 只入队，flush 时严格按入队顺序执行")
    void bufferDefersAndFlushesInOrder() {
        LiveEventGate gate = new LiveEventGate();
        List<String> ran = new ArrayList<>();
        String result = gate.buffer(() -> {
            assertThat(gate.isBuffering()).isTrue();
            gate.emit(() -> ran.add("first"));
            gate.emit(() -> ran.add("second"));
            assertThat(ran).as("缓冲期间不得提前广播").isEmpty();
            return "ok";
        });
        assertThat(result).isEqualTo("ok");
        assertThat(ran).containsExactly("first", "second");
        assertThat(gate.isBuffering()).isFalse();
    }

    @Test
    @DisplayName("action 抛异常时丢弃全部缓冲广播并原样抛出，不留悬挂缓冲区")
    void exceptionDiscardsBufferedEvents() {
        LiveEventGate gate = new LiveEventGate();
        List<String> ran = new ArrayList<>();
        assertThatThrownBy(() -> gate.buffer(() -> {
            gate.emit(() -> ran.add("never"));
            throw new IllegalStateException("save failed");
        })).isInstanceOf(IllegalStateException.class).hasMessage("save failed");
        assertThat(ran).as("持久化阶段失败，任何广播都不得发出").isEmpty();
        assertThat(gate.isBuffering()).as("异常后 ThreadLocal 必须清理").isFalse();

        // 缓冲区被清理后，后续 emit 恢复立即执行，证明没有残留状态污染下一次请求
        gate.emit(() -> ran.add("after"));
        assertThat(ran).containsExactly("after");
    }

    @Test
    @DisplayName("flush 时单条广播失败不影响其余广播（best-effort 语义）")
    void oneBroadcastFailureDoesNotBlockOthers() {
        LiveEventGate gate = new LiveEventGate();
        List<String> ran = new ArrayList<>();
        gate.buffer(() -> {
            gate.emit(() -> {
                throw new RuntimeException("ws session gone");
            });
            gate.emit(() -> ran.add("survivor"));
            return null;
        });
        assertThat(ran).containsExactly("survivor");
    }
}
