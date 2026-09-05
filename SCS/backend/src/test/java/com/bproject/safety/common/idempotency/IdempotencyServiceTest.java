package com.bproject.safety.common.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class IdempotencyServiceTest {
    @Mock StringRedisTemplate redis;
    @Mock ValueOperations<String, String> valueOps;
    IdempotencyService service;

    @BeforeEach
    void setUp() {
        service = new IdempotencyService(redis, 600);
        // 部分用例（blankKey/release）不访问 opsForValue，使用 lenient 避免严格模式报错
        lenient().when(redis.opsForValue()).thenReturn(valueOps);
    }

    @Test
    void firstRequestAcquiresAndCompletes() {
        when(valueOps.setIfAbsent(eq("idempotency:k1"), eq("PROCESSING"), any(Duration.class))).thenReturn(true);
        assertThat(service.acquire("k1")).isTrue();
        service.complete("k1", "RESULT-A");
        verify(valueOps).set(eq("idempotency:k1"), eq("RESULT-A"), any(Duration.class));
    }

    @Test
    void duplicateRequestIsDetected() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(false);
        assertThat(service.acquire("k1")).isFalse();
    }

    @Test
    void completedResultIsReturnedForReplay() {
        when(valueOps.get("idempotency:k1")).thenReturn("RESULT-A");
        assertThat(service.getCompletedResult("k1")).contains("RESULT-A");
    }

    @Test
    void processingValueIsNotCompletedResult() {
        when(valueOps.get("idempotency:k1")).thenReturn("PROCESSING");
        assertThat(service.getCompletedResult("k1")).isEmpty();
    }

    @Test
    void processRunsActionOnlyOnceForSameKey() {
        AtomicInteger runs = new AtomicInteger();
        when(valueOps.get("idempotency:k")).thenReturn(null, "DONE");
        when(valueOps.setIfAbsent(eq("idempotency:k"), eq("PROCESSING"), any(Duration.class))).thenReturn(true);

        String first = service.process("k", () -> {
            runs.incrementAndGet();
            return "DONE";
        });
        String second = service.process("k", () -> {
            runs.incrementAndGet();
            return "DONE-NEW";
        });

        assertThat(first).isEqualTo("DONE");
        assertThat(second).isEqualTo("DONE");
        assertThat(runs.get()).isEqualTo(1);
    }

    @Test
    void blankKeyBypassesStoreAndAlwaysRuns() {
        AtomicInteger runs = new AtomicInteger();
        service.process("", () -> String.valueOf(runs.incrementAndGet()));
        service.process(null, () -> String.valueOf(runs.incrementAndGet()));
        assertThat(runs.get()).isEqualTo(2);
        verify(redis, never()).opsForValue();
    }

    @Test
    void redisFailureDegradesToPassThrough() {
        when(valueOps.setIfAbsent(any(), any(), any(Duration.class)))
                .thenThrow(new RuntimeException("Kvrocks connection refused"));
        assertThat(service.acquire("k1")).isTrue();
        verify(valueOps, times(1)).setIfAbsent(any(), any(), any(Duration.class));
    }

    @Test
    void releaseDeletesKey() {
        service.release("k1");
        verify(redis).delete("idempotency:k1");
    }
}
