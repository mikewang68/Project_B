package com.bproject.safety.module.alert.service;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.support.concurrency.KeyedLock;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoMasterData;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("Step 2.5: Alert 单 JVM 细粒度并发防重与 KeyedLock 串行化测试")
class AlertDedupConcurrencyTest {

    private Clock clock;
    private InMemoryAlertRepository repository;
    private AlertService service;
    private KeyedLock keyedLock;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        repository = new InMemoryAlertRepository();
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        DemoMasterData masterData = new DemoMasterData();
        keyedLock = new KeyedLock();
        service = new AlertService(repository, idempotency, user, masterData, clock,
                AlertChangeNotifier.NOOP, new DemoAlertNumberGenerator(repository, clock),
                null, keyedLock);
    }

    @Test
    @DisplayName("P1-C: 8 线程高并发提交相同 dedupKey 的感知风险，最终严格仅生成 1 个聚合根且时间线不膨胀")
    void testConcurrentSameDedupKeyProducesSingleAlert() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<DemoAlert> results = Collections.synchronizedList(new ArrayList<>());
        List<Throwable> errors = Collections.synchronizedList(new ArrayList<>());

        String sharedDedupKey = "COLLISION:DEV-CONC-01:DEV-CONC-02";

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AlertService.NewRiskAlert draft = new AlertService.NewRiskAlert(
                            "设备防碰撞", sharedDedupKey, "并发防碰撞预警", "碰撞预警",
                            RiskLevels.SEVERE, "A1作业区", "DEV-01 & DEV-02",
                            "RULE-COL-001", "v1.0", "RULE", "RULE-COL-001", "v1.0",
                            10, AlertEvidence.MetricEvidence.device(List.of(), "测试证据 " + index),
                            "首条时间线", "第二条时间线");
                    DemoAlert alert = service.createRiskAlert(draft);
                    results.add(alert);
                } catch (Throwable t) {
                    errors.add(t);
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        boolean completed = executor.awaitTermination(10, TimeUnit.SECONDS);
        assertThat(completed).isTrue();

        assertThat(errors).isEmpty();
        assertThat(results).hasSize(threads);

        // 验证所有线程返回的都是同一个告警 ID
        String expectedAlertId = results.get(0).id;
        assertThat(expectedAlertId).isNotBlank();
        for (DemoAlert alert : results) {
            assertThat(alert.id).isEqualTo(expectedAlertId);
        }

        // 仓储中严格仅有 1 条告警记录
        assertThat(repository.count()).isEqualTo(1);
        DemoAlert stored = repository.findById(expectedAlertId).orElseThrow();
        // 初始时间线严格为 2 条，绝无重复膨胀
        assertThat(stored.timeline).hasSize(2);
    }

    @Test
    @DisplayName("P1-C: 多线程并发提交不同 dedupKey，能够各自并行创建独立告警")
    void testConcurrentDifferentDedupKeyProducesMultipleAlerts() throws Exception {
        int threads = 6;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<DemoAlert> results = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threads; i++) {
            final int index = i;
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    String dedupKey = "COLLISION:PAIR-" + index;
                    AlertService.NewRiskAlert draft = new AlertService.NewRiskAlert(
                            "设备防碰撞", dedupKey, "独立预警 " + index, "碰撞预警",
                            RiskLevels.WARNING, "A区", "DEV-" + index,
                            "RULE-COL-001", "v1.0", "RULE", "RULE-COL-001", "v1.0",
                            5, null, "时间线1", "时间线2");
                    results.add(service.createRiskAlert(draft));
                } catch (Exception ignored) {
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(results).hasSize(threads);
        long distinctIds = results.stream().map(a -> a.id).distinct().count();
        assertThat(distinctIds).isEqualTo(threads);
        assertThat(repository.count()).isEqualTo(threads);
    }

    @Test
    @DisplayName("P1-B/P1-C: 8 线程并发调用 createEdgeReplayAlert 补传同一离线事件，严格仅生成 1 个告警")
    void testConcurrentEdgeReplayProducesSingleAlert() throws Exception {
        int threads = 8;
        ExecutorService executor = Executors.newFixedThreadPool(threads);
        CountDownLatch readyLatch = new CountDownLatch(threads);
        CountDownLatch startLatch = new CountDownLatch(1);
        List<DemoAlert> results = Collections.synchronizedList(new ArrayList<>());

        OffsetDateTime occurredAt = OffsetDateTime.now(clock).minusMinutes(20);

        for (int i = 0; i < threads; i++) {
            executor.submit(() -> {
                readyLatch.countDown();
                try {
                    startLatch.await();
                    AlertService.EdgeReplayDraft draft = new AlertService.EdgeReplayDraft(
                            "EDGE-01", "OFFLINE-CONC-001", occurredAt,
                            "边缘感知", "边缘离线碰撞预警", "碰撞预警", "严重", "A作业区",
                            "DEV-01 & DEV-02", "RULE-COL-001", "v1.0",
                            "RULE", "RULE-COL-001", "v1.0", 10, null,
                            "距离低于阈值", "声光报警");
                    results.add(service.createEdgeReplayAlert(draft));
                } catch (Exception ignored) {
                }
            });
        }

        readyLatch.await(5, TimeUnit.SECONDS);
        startLatch.countDown();
        executor.shutdown();
        executor.awaitTermination(10, TimeUnit.SECONDS);

        assertThat(results).hasSize(threads);
        String expectedAlertId = results.get(0).id;
        for (DemoAlert a : results) {
            assertThat(a.id).isEqualTo(expectedAlertId);
        }
        assertThat(repository.count()).isEqualTo(1);
        // 初始时间线节点数量严格为 7，绝无并发膨胀
        assertThat(repository.findById(expectedAlertId).orElseThrow().timeline).hasSize(7);
    }
}
