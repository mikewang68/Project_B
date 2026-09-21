package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.module.ai.dto.AiRequests.ReviewRequest;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.realtime.AiChangeNotifier;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.repository.InMemoryAiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.ai.service.AiEventNumberGenerator;
import com.bproject.safety.module.ai.service.AiEventService;
import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertQuery;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertNumberGenerator;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.demo.DemoAiEventNumberGenerator;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoFeatureGuard;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B：AI confirm 跨聚合工作流的“先全部 save、后统一发布”测试。
 * 通过记录型 AlertRepository 包装器记录 save 时刻、lambda 通知器记录广播时刻，
 * 断言所有持久化 save 都先于任何 LiveEvent 广播（LiveEventGate 缓冲语义）。
 */
class AiConfirmEventOrderTest {

    private final List<String> trace = new ArrayList<>();
    private Clock clock;
    private AiEventRepository aiRepository;
    private AlertRepository alertRepository;
    private AiEventService aiService;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-03T14:30:00Z"), ZoneOffset.ofHours(8));
        aiRepository = new InMemoryAiEventRepository(clock);
        new AiDemoSeeder(aiRepository, clock, new DemoFeatureGuard(true, true)).buildSeeds()
                .forEach(aiRepository::save);
        alertRepository = new InMemoryAlertRepository(clock);

        DemoMasterData masterData = new DemoMasterData();
        DemoDeviceMasterData deviceMasterData = new DemoDeviceMasterData(masterData);
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        LiveEventGate gate = new LiveEventGate();

        AlertRepository recordingAlerts = new RecordingAlertRepository(alertRepository, trace);
        AlertNumberGenerator alertNumbers = new DemoAlertNumberGenerator(recordingAlerts, clock);
        AlertChangeNotifier alertNotifier =
                (op, after) -> trace.add("notify-alert:" + op + ":" + after.id);
        AlertService alertService = new AlertService(recordingAlerts, idempotency, user, masterData,
                clock, alertNotifier, alertNumbers);

        AiEventNumberGenerator aiNumbers = new DemoAiEventNumberGenerator(aiRepository, clock);
        AiChangeNotifier aiNotifier = (op, after) -> trace.add("notify-ai:" + op + ":" + after.id);
        aiService = new AiEventService(aiRepository, alertService, idempotency, aiNotifier, user,
                masterData, deviceMasterData, clock, aiNumbers, gate);
    }

    @Test
    @DisplayName("AI confirm：Alert / AI 全部 save 完成后才广播 alert.new 与 ai.reviewed")
    void allSavesHappenBeforeAnyBroadcast() {
        DemoAiEvent after = aiService.confirm("AI-E-20260903-026", new ReviewRequest("李娜"), null);

        assertThat(after.linkedAlertId).isNotBlank();
        assertThat(trace).isNotEmpty();

        int firstNotify = trace.stream().filter(s -> s.startsWith("notify-")).mapToInt(trace::indexOf).min().orElseThrow();
        long savesAfterFirstNotify = trace.subList(firstNotify, trace.size()).stream()
                .filter(s -> s.startsWith("save-alert:") || s.startsWith("save-ai:"))
                .count();
        assertThat(savesAfterFirstNotify)
                .as("第一条广播发出后不得再有任何 save（杜绝半流程广播）")
                .isZero();
        assertThat(trace).anyMatch(s -> s.equals("notify-alert:new:" + after.linkedAlertId));
        assertThat(trace).anyMatch(s -> s.startsWith("notify-ai:reviewed:AI-E-20260903-026"));

        // 两个聚合确实都已落库
        assertThat(alertRepository.findById(after.linkedAlertId)).isPresent();
        assertThat(aiRepository.findById("AI-E-20260903-026").orElseThrow().linkedAlertId)
                .isEqualTo(after.linkedAlertId);
    }

    /** 记录 save 顺序的 AlertRepository 包装器，其余方法全部委托。 */
    private static final class RecordingAlertRepository implements AlertRepository {
        private final AlertRepository delegate;
        private final List<String> trace;

        RecordingAlertRepository(AlertRepository delegate, List<String> trace) {
            this.delegate = delegate;
            this.trace = trace;
        }

        @Override
        public DemoAlert save(DemoAlert alert) {
            trace.add("save-alert:" + alert.id);
            return delegate.save(alert);
        }

        @Override
        public List<DemoAlert> findAll() {
            return delegate.findAll();
        }

        @Override
        public List<DemoAlert> filter(AlertQuery query) {
            return delegate.filter(query);
        }

        @Override
        public AlertPageResult page(AlertQuery query) {
            return delegate.page(query);
        }

        @Override
        public Optional<DemoAlert> findById(String id) {
            return delegate.findById(id);
        }

        @Override
        public Optional<DemoAlert> findOpenByDedupKey(String dedupKey) {
            return delegate.findOpenByDedupKey(dedupKey);
        }

        @Override
        public long count() {
            return delegate.count();
        }
    }
}
