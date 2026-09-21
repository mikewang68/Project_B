package com.bproject.safety.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.ai.dto.AiRequests.FalsePositiveRequest;
import com.bproject.safety.module.ai.dto.AiRequests.ReviewRequest;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.realtime.AiChangeNotifier;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.demo.DemoUserProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/**
 * AI 复核 → Alert 联动的实时广播 / 幂等单元测试：
 * 确认违规同时发布 ai.reviewed 与 alert.new；误报只发布 ai.reviewed、不发布 alert.new；
 * 相同 Idempotency-Key 重复确认只创建一个 Alert。
 */
class AiEventRealtimeTest {

    private AiEventRepository aiRepository;
    private AlertRepository alertRepository;
    private StringRedisTemplate redis;
    private ValueOperations<String, String> ops;
    private final List<String> alertOps = new ArrayList<>();
    private final List<String> aiOps = new ArrayList<>();
    private AiEventService aiService;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-09-03T14:30:00Z"), ZoneOffset.ofHours(8));
        alertRepository = new InMemoryAlertRepository();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
        aiRepository = new InMemoryAiEventRepository();
        com.bproject.safety.support.demo.DemoFeatureGuard guard =
                new com.bproject.safety.support.demo.DemoFeatureGuard(true, true);
        com.bproject.safety.support.masterdata.DemoMasterData masterData =
                new com.bproject.safety.support.masterdata.DemoMasterData();
        com.bproject.safety.support.masterdata.DemoDeviceMasterData deviceMasterData =
                new com.bproject.safety.support.masterdata.DemoDeviceMasterData(masterData);
        new AiDemoSeeder(aiRepository, clock, guard).buildSeeds().forEach(aiRepository::save);

        redis = mock(StringRedisTemplate.class);
        ops = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(ops);
        IdempotencyService idempotency = new IdempotencyService(redis, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);

        AlertChangeNotifier alertNotifier = (op, after) -> alertOps.add(op + ":" + after.id);
        AiChangeNotifier aiNotifier = (op, after) -> aiOps.add(op + ":" + after.id);
        // Phase B：编号 Generator 与 LiveEventGate 为显式依赖。
        com.bproject.safety.module.alert.service.AlertNumberGenerator alertNumbers =
                new com.bproject.safety.support.demo.DemoAlertNumberGenerator(alertRepository, clock);
        com.bproject.safety.module.ai.service.AiEventNumberGenerator aiNumbers =
                new com.bproject.safety.support.demo.DemoAiEventNumberGenerator(aiRepository, clock);
        com.bproject.safety.common.realtime.LiveEventGate gate =
                new com.bproject.safety.common.realtime.LiveEventGate();
        AlertService alertService = new AlertService(alertRepository, idempotency, user, masterData, clock,
                alertNotifier, alertNumbers);
        aiService = new AiEventService(aiRepository, alertService, idempotency, aiNotifier, user,
                masterData, deviceMasterData, clock, aiNumbers, gate);
    }

    @Test
    @DisplayName("确认违规：发布 ai.reviewed 且发布 alert.new（同一 alertId 写回 AIEvent）")
    void confirmPublishesAiAndAlert() {
        DemoAiEvent after = aiService.confirm("AI-E-20260903-026", new ReviewRequest("李娜"), null);
        assertThat(after.linkedAlertId).isNotBlank();
        assertThat(aiOps).anyMatch(x -> x.startsWith("reviewed:") && x.endsWith("026"));
        assertThat(alertOps).contains("new:" + after.linkedAlertId);
    }

    @Test
    @DisplayName("误报：只发布 ai.reviewed，绝不发布 alert.new")
    void falsePositivePublishesNoAlert() {
        long before = alertRepository.findAll().size();
        aiService.falsePositive("AI-E-20260903-023",
                new FalsePositiveRequest("遮挡误判", "李娜", null), null);
        assertThat(aiOps).anyMatch(x -> x.startsWith("reviewed:") && x.endsWith("023"));
        assertThat(alertOps.stream().filter(x -> x.startsWith("new:")).toList()).isEmpty();
        assertThat(alertRepository.findAll().size()).isEqualTo(before);
    }

    @Test
    @DisplayName("相同 Idempotency-Key 重复确认：第二次回放，只创建一个 Alert、alert.new 只发一次")
    void sameIdempotencyKeyCreatesAlertOnce() {
        String key = "idem-ai-confirm-024";
        String storeKey = IdempotencyService.KEY_PREFIX + key;
        when(ops.get(storeKey)).thenReturn(null, "reviewed:AI-E-20260903-024");
        when(ops.setIfAbsent(eq(storeKey), eq(IdempotencyService.PROCESSING), any())).thenReturn(true);

        long before = alertRepository.findAll().size();
        DemoAiEvent first = aiService.confirm("AI-E-20260903-024", new ReviewRequest("李娜"), key);
        DemoAiEvent second = aiService.confirm("AI-E-20260903-024", new ReviewRequest("李娜"), key);

        assertThat(first.linkedAlertId).isNotBlank();
        assertThat(second.linkedAlertId).isEqualTo(first.linkedAlertId);
        assertThat(alertRepository.findAll().size()).isEqualTo(before + 1);
        assertThat(alertOps.stream().filter(x -> x.startsWith("new:")).count()).isEqualTo(1);
        verify(ops, times(1)).setIfAbsent(eq(storeKey), eq(IdempotencyService.PROCESSING), any());
    }
}
