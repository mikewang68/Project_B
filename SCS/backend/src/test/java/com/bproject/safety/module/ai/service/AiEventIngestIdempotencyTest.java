package com.bproject.safety.module.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.module.ai.dto.AiRequests.AiIngestRequest;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.realtime.AiChangeNotifier;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.demo.DemoAiEventNumberGenerator;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

@ExtendWith(MockitoExtension.class)
class AiEventIngestIdempotencyTest {

    private static final String IDEM_KEY = "idem-ingest-001";
    private static final String STORE_KEY = IdempotencyService.KEY_PREFIX + IDEM_KEY;

    @Mock
    private StringRedisTemplate redis;
    @Mock
    private ValueOperations<String, String> ops;

    private InMemoryAiEventRepository repository;
    private AiEventService service;

    @BeforeEach
    void setUp() {
        Clock clock = Clock.fixed(Instant.parse("2026-10-06T05:30:00Z"), ZoneOffset.ofHours(8));
        repository = new InMemoryAiEventRepository();
        DemoMasterData masterData = new DemoMasterData();
        DemoDeviceMasterData deviceMasterData = new DemoDeviceMasterData(masterData);
        com.bproject.safety.support.demo.DemoFeatureGuard guard =
                new com.bproject.safety.support.demo.DemoFeatureGuard(true, true);
        AiDemoSeeder aiSeeder = new AiDemoSeeder(repository, clock, guard);
        aiSeeder.buildSeeds().forEach(repository::save);

        IdempotencyService idempotency = new IdempotencyService(redis, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        InMemoryAlertRepository alertRepo = new InMemoryAlertRepository();
        AlertService alertService = new AlertService(alertRepo, idempotency, user, masterData, clock,
                AlertChangeNotifier.NOOP, new DemoAlertNumberGenerator(alertRepo, clock));

        service = new AiEventService(repository, alertService, idempotency, AiChangeNotifier.NOOP,
                user, masterData, deviceMasterData, clock,
                new DemoAiEventNumberGenerator(repository, clock), new LiveEventGate());
    }

    @Test
    @DisplayName("相同 Idempotency-Key 重复上报：第二次回放首次生成的事件，不重复新增")
    void duplicateKeyReplaysSameEvent() {
        when(redis.opsForValue()).thenReturn(ops);
        // 第一次调用：Kvrocks 无缓存，占用成功
        when(ops.get(STORE_KEY)).thenReturn(null);
        when(ops.setIfAbsent(eq(STORE_KEY), eq(IdempotencyService.PROCESSING), any())).thenReturn(true);

        AiIngestRequest request = new AiIngestRequest(
                "未佩戴安全帽", "CAM-01", "装卸区 1 号枪机", "装卸区 A",
                95.0, 80.0, 1.0, "YOLOv8", "HIGH", "helmet",
                "RULE-01", "诊断文案", null, List.of(), null, null, OffsetDateTime.now()
        );

        DemoAiEvent first = service.ingest(request, IDEM_KEY);
        assertThat(first).isNotNull();
        String generatedId = first.id;

        // 第二次调用同 key：Kvrocks 已存有首次结果 generatedId
        when(ops.get(STORE_KEY)).thenReturn(generatedId);

        DemoAiEvent second = service.ingest(request, IDEM_KEY);
        assertThat(second).isNotNull();
        assertThat(second.id).isEqualTo(generatedId);

        // setIfAbsent 只在首次占用时调用 1 次
        verify(ops, times(1)).setIfAbsent(eq(STORE_KEY), eq(IdempotencyService.PROCESSING), any());
    }
}
