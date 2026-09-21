package com.bproject.safety.module.alert.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.support.demo.DemoUserProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * 验证写操作成功后触发实时通知；幂等重复回放不重复通知。
 */
@ExtendWith(MockitoExtension.class)
class AlertChangeNotifierTest {

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> ops;
    @Mock
    AlertChangeNotifier notifier;

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
        service = new AlertService(repository, idempotency, user, masterData, clock, notifier,
                new com.bproject.safety.support.demo.DemoAlertNumberGenerator(repository, clock));
    }

    @Test
    @DisplayName("confirm 成功后通知一次，op=confirm，实体为最新状态")
    void notifierFiredOnMutation() {
        DemoAlert result = service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        verify(notifier, times(1)).changed(eq("confirm"), any(DemoAlert.class));
        assertThat(result.statusCode).isEqualTo(AlertStatuses.PENDING_ASSIGNMENT);
    }

    @Test
    @DisplayName("相同幂等键回放：不执行业务变更，也不重复通知")
    void notifierSkippedOnIdempotentReplay() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(any())).thenReturn(null, "confirm:ALM-20260904-002");
        when(ops.setIfAbsent(any(), any(), any())).thenReturn(true);

        service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), "k1");
        service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), "k1");
        verify(notifier, times(1)).changed(eq("confirm"), any(DemoAlert.class));
    }

    @Test
    @DisplayName("非法状态流转抛 409 时不发通知")
    void notifierNotFiredOnConflict() {
        // 008 已关闭，confirm 非法
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                service.confirm("ALM-20260904-008", new ConfirmRequest("李娜"), null)).isInstanceOf(RuntimeException.class);
        verify(notifier, never()).changed(any(), any());
    }

    @Test
    @DisplayName("assign 后 mobileStage 初始化为 PENDING")
    void assignSetsMobileStagePending() {
        service.confirm("ALM-20260904-002", new ConfirmRequest("李娜"), null);
        DemoAlert assigned = service.assign("ALM-20260904-002",
                new AssignRequest("王建国", "USR-002", "王建国", "普通", 15, null, null, null), null);
        assertThat(assigned.statusCode).isEqualTo(AlertStatuses.PENDING_PROCESS);
        assertThat(assigned.mobileStage).isEqualTo("PENDING");
    }
}
