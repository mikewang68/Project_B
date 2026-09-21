package com.bproject.safety.support.phaseb;

import static org.assertj.core.api.Assertions.assertThat;

import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.service.AlertChangeNotifier;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.EdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryOpsEventLogRepository;
import com.bproject.safety.module.ops.repository.OpsEventLogRepository;
import com.bproject.safety.module.ops.realtime.OpsLiveNotifier;
import com.bproject.safety.module.ops.service.EdgeReplayService;
import com.bproject.safety.support.demo.DemoAlertNumberGenerator;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase B：边缘补传幂等权威来自队列仓储（不再依赖 Service 进程内 HashSet）。
 * 覆盖：同 eventId 重复补传、同 idempotencyKey 跨事件去重、Service 重建后判重仍有效、
 * 首次失败（PLATFORM_TEMPORARILY_UNAVAILABLE）→ FAILED → 重试 SYNCED。
 */
class EdgeReplayIdempotencyTest {

    private Clock clock;
    private EdgeEventQueueRepository queue;
    private EdgeNodeRepository nodes;
    private AlertRepository alerts;
    private OpsEventLogRepository logs;
    private AlertService alertService;
    private OpsLiveNotifier opsNotifier;
    private LiveEventGate gate;
    private DemoEdgeNode node;

    @BeforeEach
    void setUp() {
        clock = Clock.fixed(Instant.parse("2026-09-04T05:30:00Z"), ZoneOffset.ofHours(8));
        DemoMasterData masterData = new DemoMasterData();
        DemoDeviceMasterData deviceMasterData = new DemoDeviceMasterData(masterData);
        queue = new InMemoryEdgeEventQueueRepository();
        logs = new InMemoryOpsEventLogRepository();
        nodes = new InMemoryEdgeNodeRepository(clock, deviceMasterData);
        ((InMemoryEdgeNodeRepository) nodes).resetDemoData();
        node = nodes.findById("EDGE-01").orElseThrow();

        alerts = new InMemoryAlertRepository(clock);
        IdempotencyService idempotency = new IdempotencyService(null, 600);
        DemoUserProperties user = new DemoUserProperties("USR-001", "李娜", "安全员", "安全管理组", "夜班", true);
        gate = new LiveEventGate();
        alertService = new AlertService(alerts, idempotency, user, masterData, clock,
                AlertChangeNotifier.NOOP, new DemoAlertNumberGenerator(alerts, clock));
        DomainLivePublisher publisher =
                new DomainLivePublisher(new WebSocketSessionRegistry(), new ObjectMapper(), clock, gate);
        opsNotifier = new OpsLiveNotifier(publisher);
    }

    private EdgeReplayService newReplayService() {
        return new EdgeReplayService(queue, logs, alertService, opsNotifier, clock, gate);
    }

    private EdgePendingEvent pending(String eventId, String idemKey, OffsetDateTime occurredAt) {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = eventId;
        e.edgeNodeId = "EDGE-01";
        e.eventType = "person-intrusion";
        e.businessKey = "BK-" + eventId;
        e.idempotencyKey = idemKey;
        e.status = PendingEventStatuses.PENDING;
        e.edgeOccurredAt = occurredAt;
        e.receivedAt = occurredAt;
        e.risk = "严重";
        e.localLinkage = EdgeLocalLinkage.success(new ArrayList<>(), false);
        return e;
    }

    @Test
    @DisplayName("同一 eventId 第二次补传判 DUPLICATE，只建一张 Alert")
    void sameEventIdReplayedTwiceIsDuplicate() {
        EdgeReplayService replay = newReplayService();
        OffsetDateTime occurredAt = OffsetDateTime.now(clock).minusMinutes(10);
        EdgePendingEvent e = pending("EDGE-EVT-DUP-01", "IDEM-DUP-01", occurredAt);
        queue.save(e);

        EdgeReplayService.ReplayResult first = replay.replayOne(queue.findByEventId(e.eventId).orElseThrow(), node);
        assertThat(first.status()).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(first.alreadyProcessed()).isFalse();
        assertThat(first.alertId()).isNotBlank();
        long alertsAfterFirst = alerts.count();

        EdgeReplayService.ReplayResult second =
                replay.replayOne(queue.findByEventId(e.eventId).orElseThrow(), node);
        assertThat(second.status()).isEqualTo(PendingEventStatuses.DUPLICATE);
        assertThat(second.alreadyProcessed()).isTrue();
        assertThat(second.alertId()).isEqualTo(first.alertId());
        assertThat(alerts.count()).isEqualTo(alertsAfterFirst);
        assertThat(queue.findByEventId(e.eventId).orElseThrow().status)
                .isEqualTo(PendingEventStatuses.SYNCED);
    }

    @Test
    @DisplayName("不同 eventId 但相同 idempotencyKey：第二个判 DUPLICATE 并回指原告警")
    void sameIdempotencyKeyAcrossEventsIsDuplicate() {
        EdgeReplayService replay = newReplayService();
        OffsetDateTime base = OffsetDateTime.now(clock).minusMinutes(10);
        EdgePendingEvent first = pending("EDGE-EVT-KEY-A", "IDEM-SAME-KEY", base);
        EdgePendingEvent second = pending("EDGE-EVT-KEY-B", "IDEM-SAME-KEY", base.plusMinutes(1));
        queue.save(first);
        queue.save(second);

        EdgeReplayService.ReplayResult r1 = replay.replayOne(queue.findByEventId("EDGE-EVT-KEY-A").orElseThrow(), node);
        EdgeReplayService.ReplayResult r2 = replay.replayOne(queue.findByEventId("EDGE-EVT-KEY-B").orElseThrow(), node);

        assertThat(r1.status()).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(r2.status()).isEqualTo(PendingEventStatuses.DUPLICATE);
        assertThat(r2.alertId()).isEqualTo(r1.alertId());
        assertThat(queue.findByEventId("EDGE-EVT-KEY-B").orElseThrow().status)
                .isEqualTo(PendingEventStatuses.DUPLICATE);
    }

    @Test
    @DisplayName("判重权威在仓储：重建 EdgeReplayService（模拟服务重启）后重复补传仍判 DUPLICATE")
    void dedupSurvivesServiceRecreation() {
        OffsetDateTime occurredAt = OffsetDateTime.now(clock).minusMinutes(10);
        queue.save(pending("EDGE-EVT-RESTART-01", "IDEM-RESTART-01", occurredAt));

        EdgeReplayService.ReplayResult first = newReplayService()
                .replayOne(queue.findByEventId("EDGE-EVT-RESTART-01").orElseThrow(), node);
        assertThat(first.status()).isEqualTo(PendingEventStatuses.SYNCED);

        // 全新的 Service 实例：进程内没有任何 HashSet 记忆，判重只能来自仓储
        EdgeReplayService.ReplayResult again = newReplayService()
                .replayOne(queue.findByEventId("EDGE-EVT-RESTART-01").orElseThrow(), node);
        assertThat(again.status()).isEqualTo(PendingEventStatuses.DUPLICATE);
        assertThat(again.alertId()).isEqualTo(first.alertId());
    }

    @Test
    @DisplayName("failNextReplay：首次 FAILED（PLATFORM_TEMPORARILY_UNAVAILABLE、retryCount=1），重试 SYNCED")
    void firstFailureThenRetrySucceeds() {
        EdgeReplayService replay = newReplayService();
        OffsetDateTime occurredAt = OffsetDateTime.now(clock).minusMinutes(10);
        EdgePendingEvent e = pending("EDGE-EVT-FAIL-01", "IDEM-FAIL-01", occurredAt);
        e.failNextReplay = true;
        queue.save(e);

        EdgeReplayService.ReplayResult failed =
                replay.replayOne(queue.findByEventId("EDGE-EVT-FAIL-01").orElseThrow(), node);
        assertThat(failed.status()).isEqualTo(PendingEventStatuses.FAILED);
        assertThat(failed.errorCode()).isEqualTo(EdgeReplayService.ERR_PLATFORM_UNAVAILABLE);
        EdgePendingEvent afterFailure = queue.findByEventId("EDGE-EVT-FAIL-01").orElseThrow();
        assertThat(afterFailure.status).isEqualTo(PendingEventStatuses.FAILED);
        assertThat(afterFailure.retryCount).isEqualTo(1);
        assertThat(afterFailure.lastError).isEqualTo(EdgeReplayService.ERR_PLATFORM_UNAVAILABLE);
        assertThat(afterFailure.failNextReplay).as("失败标记为一次性，重试不得再次失败").isFalse();
        long alertsBeforeRetry = alerts.count();

        EdgeReplayService.ReplayResult retried =
                replay.replayOne(queue.findByEventId("EDGE-EVT-FAIL-01").orElseThrow(), node);
        assertThat(retried.status()).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(retried.alertId()).isNotBlank();
        assertThat(alerts.count()).isEqualTo(alertsBeforeRetry + 1);
        EdgePendingEvent afterRetry = queue.findByEventId("EDGE-EVT-FAIL-01").orElseThrow();
        assertThat(afterRetry.status).isEqualTo(PendingEventStatuses.SYNCED);
        assertThat(afterRetry.linkedAlertId).isEqualTo(retried.alertId());
        assertThat(afterRetry.retryCount).isEqualTo(1);
    }
}
