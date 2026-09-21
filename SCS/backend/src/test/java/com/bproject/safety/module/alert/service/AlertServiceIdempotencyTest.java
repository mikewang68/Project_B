package com.bproject.safety.module.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.support.demo.DemoUserProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 幂等单元测试：用 Mockito 模拟 Kvrocks（Redis 协议）行为，
 * 验证相同 Idempotency-Key 的重复写请求不会重复变更状态、重复追加时间线。
 */
@ExtendWith(MockitoExtension.class)
class AlertServiceIdempotencyTest {

    private static final String IDEM_KEY = "idem-confirm-001";
    private static final String STORE_KEY = IdempotencyService.KEY_PREFIX + IDEM_KEY;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;

    private InMemoryAlertRepository repository;
    private AlertService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        repository = new InMemoryAlertRepository();
        AlertDemoSeeder.buildSeeds(clock).forEach(repository::save);
        IdempotencyService idempotency = new IdempotencyService(redis, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        com.bproject.safety.support.masterdata.DemoMasterData masterData =
                new com.bproject.safety.support.masterdata.DemoMasterData();
        service = new AlertService(repository, idempotency, user, masterData, clock, AlertChangeNotifier.NOOP,
                new com.bproject.safety.support.demo.DemoAlertNumberGenerator(repository, clock));
    }

    @Test
    @DisplayName("相同 Idempotency-Key 重复提交：第二次回放结果，不重复追加时间线")
    void duplicateKeyDoesNotMutateAgain() {
        when(redis.opsForValue()).thenReturn(ops);
        // 第一次：Kvrocks 无记录 → 占用成功
        when(ops.get(STORE_KEY)).thenReturn(null, "confirm:ALM-20260904-002");
        when(ops.setIfAbsent(eq(STORE_KEY), eq(IdempotencyService.PROCESSING), any())).thenReturn(true);

        DemoAlert first = service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), IDEM_KEY);
        assertThat(first.statusCode).isEqualTo(AlertStatuses.PENDING_ASSIGNMENT);
        assertThat(first.timeline).hasSize(3);

        // 第二次同 key：Kvrocks 已有完成结果 → 直接回放，不再执行业务变更
        DemoAlert second = service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), IDEM_KEY);
        assertThat(second.statusCode).isEqualTo(AlertStatuses.PENDING_ASSIGNMENT);
        assertThat(second.timeline).hasSize(3);
        // setIfAbsent 只在首次调用一次，重复请求不再尝试占键
        verify(ops, times(1)).setIfAbsent(eq(STORE_KEY), eq(IdempotencyService.PROCESSING), any());
    }

    @Test
    @DisplayName("不同 Idempotency-Key 会重新进入状态机：已变更状态再次 confirm 抛 409")
    void differentKeyHitsStateMachine() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(any())).thenReturn(null);
        when(ops.setIfAbsent(any(), eq(IdempotencyService.PROCESSING), any())).thenReturn(true);

        service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), "key-a");
        assertThatThrownBy(() -> service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), "key-b"))
                .isInstanceOf(ApiException.class)
                .satisfies(ex -> assertThat(((ApiException) ex).status().value()).isEqualTo(409));
    }

    @Test
    @DisplayName("无 Idempotency-Key 时不访问 Kvrocks，业务正常执行（本地降级路径）")
    void noKeyBypassesStore() {
        DemoAlert alert = service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        assertThat(alert.statusCode).isEqualTo(AlertStatuses.PENDING_ASSIGNMENT);
        verify(redis, never()).opsForValue();
    }
}
