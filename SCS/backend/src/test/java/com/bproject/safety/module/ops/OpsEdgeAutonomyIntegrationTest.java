package com.bproject.safety.module.ops;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.bproject.safety.common.realtime.WebSocketSessionRegistry;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.InMemoryAlertRepository;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryOpsEventLogRepository;
import com.bproject.safety.module.ops.service.EdgeOpsService;
import com.bproject.safety.module.ops.service.EdgeReplayService;
import com.bproject.safety.module.ops.service.OpsInventory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * 运维监控 + 云边断网自治后端集成测试（任务书第六十节清单）。
 * 重点验收：断网期间本地判定 / 联动继续但不建云端 Alert；恢复分阶段对账、顺序幂等补传、
 * occurredAt 保持边缘时间、重复 replay 不重复建单、失败可重试、ops.* WS 事件发布。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OpsEdgeAutonomyIntegrationTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private EdgeOpsService opsService;
    @Autowired
    private EdgeReplayService replayService;
    @Autowired
    private InMemoryEdgeNodeRepository nodeRepository;
    @Autowired
    private EdgeEventQueueRepository queueRepository;
    @Autowired
    private InMemoryOpsEventLogRepository logRepository;
    @Autowired
    private OpsInventory inventory;
    @Autowired
    private AlertRepository alertRepository;
    @Autowired
    private WebSocketSessionRegistry wsRegistry;
    @Autowired
    private Clock clock;

    @BeforeEach
    void reset() {
        nodeRepository.resetDemoData();
        ((com.bproject.safety.support.demo.DemoClearableStore) queueRepository).clearDemoData();
        logRepository.clearDemoData();
        // Phase B：补传幂等判重权威来自队列仓储，清空队列即完成幂等复位，无需 Service 进程内 reset。
        inventory.reset();
        InMemoryAlertRepository alerts = (InMemoryAlertRepository) alertRepository;
        alerts.clearDemoData();
        AlertDemoSeeder.buildSeeds(clock).forEach(alertRepository::save);
    }

    private long alertCount() {
        return alertRepository.findAll().size();
    }

    @Test
    @DisplayName("运维总览：4 节点实时聚合 + DEV 环境 + 6 项基础设施健康 + SIMULATED 标记")
    void overviewAggregatedFromRepositories() throws Exception {
        mvc.perform(get("/api/v1/ops/overview"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.environment", is("DEV")))
                .andExpect(jsonPath("$.simulatedEdgeAutonomy", is(true)))
                .andExpect(jsonPath("$.edgeNodesTotal", is(4)))
                .andExpect(jsonPath("$.edgeNodesOnline", is(4)))
                .andExpect(jsonPath("$.edgeNodesOffline", is(0)))
                .andExpect(jsonPath("$.pendingSyncEvents", is(0)))
                .andExpect(jsonPath("$.ruleMismatchNodes", is(0)))
                .andExpect(jsonPath("$.components", hasSize(6)))
                .andExpect(jsonPath("$.alertPipelineStatus", notNullValue()))
                .andExpect(jsonPath("$.lastUpdated", notNullValue()));
    }

    @Test
    @DisplayName("节点列表 / 详情：4 节点、详情含趋势与本地事件")
    void nodeListAndDetail() throws Exception {
        mvc.perform(get("/api/v1/ops/edge-nodes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list", hasSize(4)));
        mvc.perform(get("/api/v1/ops/edge-nodes/EDGE-03"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.id", is("EDGE-03")))
                .andExpect(jsonPath("$.node.status", is(EdgeNodeStatuses.ONLINE)))
                .andExpect(jsonPath("$.trend.cpu", hasSize(30)))
                .andExpect(jsonPath("$.localEvents", notNullValue()));
        mvc.perform(get("/api/v1/ops/edge-nodes/UNKNOWN")).andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("拓扑：Cloud + 4 Edge，初始全部连接")
    void topology() throws Exception {
        mvc.perform(get("/api/v1/ops/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nodes", hasSize(4)))
                .andExpect(jsonPath("$.link.state", is("online")));
    }

    @Test
    @DisplayName("模拟断网：OFFLINE + 云断 + 自治激活 + 心跳延迟清空 + 规则冻结在上一版 v3.2")
    void simulateOffline() throws Exception {
        mvc.perform(post("/api/v1/edge/simulate-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"disconnect","nodeId":"EDGE-03"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(EdgeNodeStatuses.OFFLINE)))
                .andExpect(jsonPath("$.cloudConnected", is(false)))
                .andExpect(jsonPath("$.autonomyActive", is(true)))
                .andExpect(jsonPath("$.activeRuleVersion", is("v3.2")))
                .andExpect(jsonPath("$.expectedRuleVersion", is("v3.3")));
        // 重复断网 → 409，状态不被重复破坏
        mvc.perform(post("/api/v1/edge/simulate-link")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"state":"disconnect","nodeId":"EDGE-03"}
                                """))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/v1/ops/events").param("nodeId", "EDGE-03").param("type", "NODE_OFFLINE"))
                .andExpect(jsonPath("$.list", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("断网期间本地风险：本地判定 + 本地联动继续，入离线队列，但不创建云端 Alert、不增长告警数")
    void offlineLocalEventQueuedWithoutCloudAlert() throws Exception {
        long before = alertCount();
        disconnect("EDGE-03");
        mvc.perform(post("/api/v1/edge/local-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeId":"EDGE-03","eventType":"person-intrusion"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(PendingEventStatuses.PENDING)))
                .andExpect(jsonPath("$.ruleVersionUsed", is("v3.2")))
                .andExpect(jsonPath("$.localLinkage.alarm", is(true)))
                .andExpect(jsonPath("$.localLinkage.localVoice", is(true)))
                .andExpect(jsonPath("$.localLinkage.plcStop", is("success")))
                .andExpect(jsonPath("$.clockOffsetAtOccurrence", notNullValue()));
        // 关键验收：云端 Alert 数量不变
        org.junit.jupiter.api.Assertions.assertEquals(before, alertCount(), "断网期间不得创建云端 Alert");
        mvc.perform(get("/api/v1/edge/local-events").param("node", "EDGE-03"))
                .andExpect(jsonPath("$.list", hasSize(1)));
        DemoEdgeNode node = opsService.getNode("EDGE-03");
        org.junit.jupiter.api.Assertions.assertEquals(1, node.queueDepth);
        org.junit.jupiter.api.Assertions.assertTrue(node.cachedEventCount >= 1);
    }

    @Test
    @DisplayName("在线节点不允许产生离线本地事件（应直接走云端）→ 409")
    void onlineNodeRejectsLocalEvent() throws Exception {
        mvc.perform(post("/api/v1/edge/local-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"nodeId":"EDGE-01","eventType":"person-intrusion"}
                                """))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("完整恢复链：CONNECTIVITY/CLOCK 自动通过 → RULE 阻塞(v3.2≠v3.3) → redeliver → EVENT_REPLAY 补传 → ONLINE")
    void fullRecoveryWithRuleReconcile() throws Exception {
        disconnect("EDGE-03");
        createLocalEvent("EDGE-03", "person-intrusion");
        createLocalEvent("EDGE-03", "collision-risk");
        long before = alertCount();

        // 第一次 recover：连接恢复、时钟通过，规则版本不一致阻塞
        mvc.perform(post("/api/v1/edge/recover")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"nodeId":"EDGE-03"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(EdgeNodeStatuses.RECOVERING)))
                .andExpect(jsonPath("$.cloudConnected", is(true)))
                .andExpect(jsonPath("$.recoveryPhases[0].key", is("CONNECTIVITY")))
                .andExpect(jsonPath("$.recoveryPhases[0].status", is("DONE")))
                .andExpect(jsonPath("$.recoveryPhases[1].status", is("DONE")))
                .andExpect(jsonPath("$.recoveryPhases[2].status", is("BLOCKED")));

        // 规则对账视图显示不一致
        mvc.perform(get("/api/v1/edge/reconcile/rules"))
                .andExpect(jsonPath("$.expectedRuleVersion", is("v3.3")));

        // redeliver 后自动推进：补传 2 条 → FINAL → ONLINE
        mvc.perform(post("/api/v1/edge/reconcile/rules").param("nodeId", "EDGE-03")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"action":"redeliver"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is(EdgeNodeStatuses.ONLINE)))
                .andExpect(jsonPath("$.activeRuleVersion", is("v3.3")))
                .andExpect(jsonPath("$.autonomyActive", is(false)))
                .andExpect(jsonPath("$.queueDepth", is(0)));

        org.junit.jupiter.api.Assertions.assertEquals(before + 2, alertCount(), "补传后应新增 2 条云端 Alert");
        List<EdgePendingEvent> events = queueRepository.query("EDGE-03", null);
        org.junit.jupiter.api.Assertions.assertTrue(
                events.stream().allMatch(e -> PendingEventStatuses.SYNCED.equals(e.status)));
        org.junit.jupiter.api.Assertions.assertTrue(events.stream().allMatch(e -> e.linkedAlertId != null));
    }

    @Test
    @DisplayName("补传 Alert 的 occurredAt 保持边缘发生时间，syncedAt/延迟正确，origin=EDGE_REPLAY，Timeline 七节点")
    void replayKeepsEdgeOccurredAt() {
        DemoEdgeNode node = opsService.simulateDisconnect("EDGE-03");
        OffsetDateTime edgeAt = OffsetDateTime.now(clock.withZone(ZONE)).minusSeconds(900);
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = "EDGE-EVT-OLD-01";
        e.edgeNodeId = "EDGE-03";
        e.eventType = "person-intrusion";
        e.businessKey = "EDGE-03:person-intrusion:old";
        e.edgeOccurredAt = edgeAt;
        e.receivedAt = edgeAt;
        e.idempotencyKey = "EDGE-03:person-intrusion:old";
        e.ruleVersionUsed = node.activeRuleVersion;
        e.risk = "紧急";
        e.status = PendingEventStatuses.PENDING;
        queueRepository.save(e);

        opsService.startRecovery("EDGE-03"); // 阻塞在 RULE
        DemoEdgeNode recovered = opsService.reconcileRules("EDGE-03", "redeliver");
        org.junit.jupiter.api.Assertions.assertEquals(EdgeNodeStatuses.ONLINE, recovered.status);

        EdgePendingEvent synced = queueRepository.findByEventId("EDGE-EVT-OLD-01").orElseThrow();
        DemoAlert alert = alertRepository.findById(synced.linkedAlertId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(edgeAt.toEpochSecond(), alert.occurredAt.toEpochSecond());
        org.junit.jupiter.api.Assertions.assertEquals("EDGE_REPLAY", alert.origin);
        org.junit.jupiter.api.Assertions.assertTrue(alert.edgeReplay.offlineOccurred);
        org.junit.jupiter.api.Assertions.assertEquals("EDGE-03", alert.edgeReplay.edgeNodeId);
        org.junit.jupiter.api.Assertions.assertTrue(alert.edgeReplay.syncDelaySec >= 890);
        org.junit.jupiter.api.Assertions.assertEquals(7, alert.timeline.size());
    }

    @Test
    @DisplayName("重复补传幂等：第二次 replay 不再建 Alert，标记 DUPLICATE，原 alertId 保持")
    void duplicateReplayDoesNotCreateAlert() {
        opsService.simulateDisconnect("EDGE-03");
        EdgePendingEvent e = opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(
                "EDGE-03", "collision-risk", null, null, null, null));
        opsService.startRecovery("EDGE-03");
        opsService.reconcileRules("EDGE-03", "redeliver");
        long afterFirst = alertCount();
        EdgePendingEvent synced = queueRepository.findByEventId(e.eventId).orElseThrow();
        String firstAlertId = synced.linkedAlertId;
        org.junit.jupiter.api.Assertions.assertNotNull(firstAlertId);

        DemoEdgeNode node = opsService.getNode("EDGE-03");
        // 再次补传同一事件：Backend 兜底去重
        EdgeReplayService.ReplayResult result = replayService.replayOne(synced, node);
        org.junit.jupiter.api.Assertions.assertTrue(result.alreadyProcessed());
        org.junit.jupiter.api.Assertions.assertEquals(PendingEventStatuses.DUPLICATE, result.status());
        // 队列中已 SYNCED 事件保持 SYNCED，原告警 id 保持
        EdgePendingEvent after = queueRepository.findByEventId(e.eventId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PendingEventStatuses.SYNCED, after.status);
        org.junit.jupiter.api.Assertions.assertEquals(firstAlertId, result.alertId());
        org.junit.jupiter.api.Assertions.assertEquals(firstAlertId, after.linkedAlertId);
        org.junit.jupiter.api.Assertions.assertEquals(afterFirst, alertCount(), "重复补传不得新建 Alert");
    }

    @Test
    @DisplayName("故障补传：首次 replay FAILED（PLATFORM_TEMPORARILY_UNAVAILABLE，retryCount=1），再次 recover 重试成功")
    void failedReplayThenRetrySuccess() {
        opsService.simulateDisconnect("EDGE-03");
        EdgePendingEvent e = opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(
                "EDGE-03", "person-intrusion", null, null, null, null));
        opsService.armFailure(e.eventId);
        long before = alertCount();

        DemoEdgeNode recovering = opsService.startRecovery("EDGE-03"); // CONNECTIVITY/CLOCK/RULE
        // RULE 先阻塞（v3.2），对账后进入 EVENT_REPLAY 才会触发失败
        recovering = opsService.reconcileRules("EDGE-03", "redeliver");
        org.junit.jupiter.api.Assertions.assertEquals(EdgeNodeStatuses.RECOVERING, recovering.status);
        EdgePendingEvent failed = queueRepository.findByEventId(e.eventId).orElseThrow();
        org.junit.jupiter.api.Assertions.assertEquals(PendingEventStatuses.FAILED, failed.status);
        org.junit.jupiter.api.Assertions.assertEquals(1, failed.retryCount);
        org.junit.jupiter.api.Assertions.assertEquals(EdgeReplayService.ERR_PLATFORM_UNAVAILABLE, failed.lastError);
        org.junit.jupiter.api.Assertions.assertEquals(before, alertCount());

        // 再次 recover 即重试，成功并 ONLINE
        DemoEdgeNode online = opsService.startRecovery("EDGE-03");
        org.junit.jupiter.api.Assertions.assertEquals(EdgeNodeStatuses.ONLINE, online.status);
        org.junit.jupiter.api.Assertions.assertEquals(before + 1, alertCount());
        org.junit.jupiter.api.Assertions.assertEquals(PendingEventStatuses.SYNCED,
                queueRepository.findByEventId(e.eventId).orElseThrow().status);
    }

    @Test
    @DisplayName("时钟漂移：EDGE-02 +3200ms 标记 DEGRADED，校时后回到 +120ms 并记录 CLOCK_RECONCILED")
    void clockDriftAndReconcile() throws Exception {
        mvc.perform(post("/api/v1/ops/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"timeDrift","targetId":"EDGE-02"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.node.clockOffsetMs", is(3200)))
                .andExpect(jsonPath("$.node.status", is(EdgeNodeStatuses.DEGRADED)));
        mvc.perform(post("/api/v1/ops/edge-nodes/EDGE-02/maintain")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"action":"resyncTime"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.clockOffsetMs", is(120)))
                .andExpect(jsonPath("$.status", is(EdgeNodeStatuses.ONLINE)));
        mvc.perform(get("/api/v1/ops/events").param("nodeId", "EDGE-02").param("type", "CLOCK_RECONCILED"))
                .andExpect(jsonPath("$.list", hasSize(greaterThanOrEqualTo(1))));
    }

    @Test
    @DisplayName("断网恢复中时钟漂移：CLOCK 阶段阻塞，时间对账后继续推进到 RULE 阻塞")
    void recoveryBlockedByClockDrift() {
        // EDGE-03 断网并制造时钟漂移
        opsService.simulateDisconnect("EDGE-03");
        opsService.simulateTimeDrift("EDGE-03", 2800L);
        opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(
                "EDGE-03", "person-stay", null, null, null, null));
        DemoEdgeNode recovering = opsService.startRecovery("EDGE-03");
        org.junit.jupiter.api.Assertions.assertEquals("CLOCK_RECONCILIATION", recovering.recoveryPhase);
        // CLOCK 阻塞，RULE 尚未开始（WAIT）
        String ruleStatus = recovering.recoveryPhases.stream()
                .filter(p -> "RULE_RECONCILIATION".equals(p.key)).findFirst().orElseThrow().status;
        org.junit.jupiter.api.Assertions.assertEquals("WAIT", ruleStatus);
        // 校时后推进到 RULE 阻塞
        DemoEdgeNode afterClock = opsService.reconcileTime("EDGE-03");
        org.junit.jupiter.api.Assertions.assertEquals("RULE_RECONCILIATION", afterClock.recoveryPhase);
        // 规则对账后补传完成 ONLINE
        org.junit.jupiter.api.Assertions.assertEquals(EdgeNodeStatuses.ONLINE,
                opsService.reconcileRules("EDGE-03", "redeliver").status);
    }

    @Test
    @DisplayName("运维日志：可按 nodeId/type 过滤，断网/本地判定/补传/上线均有独立日志（独立于 Alert Timeline）")
    void opsEventLogQuery() throws Exception {
        disconnect("EDGE-03");
        createLocalEvent("EDGE-03", "ppe-violation");
        mvc.perform(get("/api/v1/ops/events").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.list[0].ts", notNullValue()));
        mvc.perform(get("/api/v1/ops/events").param("nodeId", "EDGE-03").param("level", "warning"))
                .andExpect(jsonPath("$.list[0].type", is("LOCAL_JUDGEMENT")));
    }

    @Test
    @DisplayName("离线队列按 occurredAt 升序、相同时间按 eventId 稳定排序补传")
    void replayOrderStable() {
        opsService.simulateDisconnect("EDGE-03");
        OffsetDateTime base = OffsetDateTime.now(clock.withZone(ZONE)).minusMinutes(5);
        // id 与时间故意交错：A-2 / A-1 同一时刻（验证同时间按 eventId），B-1 更晚
        addQueued("EDGE-EVT-B-1", base.plusSeconds(60));
        addQueued("EDGE-EVT-A-2", base.plusSeconds(30));
        addQueued("EDGE-EVT-A-1", base.plusSeconds(30));
        opsService.startRecovery("EDGE-03");
        opsService.reconcileRules("EDGE-03", "redeliver");
        List<EdgePendingEvent> synced = queueRepository.query("EDGE-03", "SYNCED");
        org.junit.jupiter.api.Assertions.assertEquals(
                List.of("EDGE-EVT-A-1", "EDGE-EVT-A-2", "EDGE-EVT-B-1"),
                synced.stream().map(x -> x.eventId).toList());
    }

    private void addQueued(String eventId, OffsetDateTime at) {
        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = eventId;
        e.edgeNodeId = "EDGE-03";
        e.eventType = "person-intrusion";
        e.businessKey = eventId;
        e.edgeOccurredAt = at;
        e.receivedAt = at;
        e.idempotencyKey = eventId;
        e.ruleVersionUsed = "v3.2";
        e.risk = "严重";
        e.status = PendingEventStatuses.PENDING;
        queueRepository.save(e);
    }

    @Test
    @DisplayName("ops.* WebSocket：断网动作广播 ops.node.changed 轻量事件")
    void opsWebSocketEventPublished() throws Exception {
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getId()).thenReturn("test-ops-ws");
        wsRegistry.connect(session);
        try {
            disconnect("EDGE-03");
            ArgumentCaptor<TextMessage> captor = ArgumentCaptor.forClass(TextMessage.class);
            verify(session, atLeastOnce()).sendMessage(captor.capture());
            boolean hasOpsNode = captor.getAllValues().stream()
                    .anyMatch(m -> m.getPayload().contains("ops.node.changed"));
            org.junit.jupiter.api.Assertions.assertTrue(hasOpsNode, "应广播 ops.node.changed");
            boolean payloadLightweight = captor.getAllValues().stream()
                    .filter(m -> m.getPayload().contains("ops.node.changed"))
                    .allMatch(m -> m.getPayload().contains("nodeId") && !m.getPayload().contains("recoveryPhases"));
            org.junit.jupiter.api.Assertions.assertTrue(payloadLightweight, "ops 事件只含轻量摘要");
        } finally {
            wsRegistry.disconnect(session);
        }
    }

    @Test
    @DisplayName("设备故障与重连、接口台账：deviceFault 后设备 fault，reconnect 恢复 online")
    void deviceFaultAndReconnect() throws Exception {
        mvc.perform(get("/api/v1/ops/devices")).andExpect(jsonPath("$.list", hasSize(60)));
        mvc.perform(get("/api/v1/ops/interfaces")).andExpect(jsonPath("$.list", hasSize(6)));
        mvc.perform(post("/api/v1/ops/simulate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"scenario":"deviceFault","targetId":"CAM-07"}
                                """))
                .andExpect(jsonPath("$.device.status", is("fault")));
        mvc.perform(post("/api/v1/ops/devices/CAM-07/reconnect"))
                .andExpect(jsonPath("$.status", is("online")));
    }

    @Test
    @DisplayName("云边 link 聚合：断网后 disconnected 且标注自治，恢复后 online")
    void cloudLinkAggregation() throws Exception {
        disconnect("EDGE-03");
        mvc.perform(get("/api/v1/edge/link"))
                .andExpect(jsonPath("$.state", is("disconnected")))
                .andExpect(jsonPath("$.simulated", is(true)));
    }

    @Test
    @DisplayName("故障注入入口：createLocalEvent(failNextReplay=true) 与 /ops/simulate replayFailure 都能武装首次失败")
    void failureInjectionEntryPoints() {
        opsService.simulateDisconnect("EDGE-03");
        EdgePendingEvent armed = opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(
                "EDGE-03", "person-intrusion", null, null, null, Boolean.TRUE));
        org.junit.jupiter.api.Assertions.assertTrue(
                ((InMemoryEdgeEventQueueRepository) queueRepository).findByEventId(armed.eventId).orElseThrow().failNextReplay,
                "createLocalEvent 的 failNextReplay 应透传到队列事件");

        EdgePendingEvent second = opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(
                "EDGE-03", "collision-risk", null, null, null, null));
        java.util.Map<String, Object> r = opsService.simulate("replayFailure", second.eventId);
        org.junit.jupiter.api.Assertions.assertEquals("replayFailure", r.get("scenario"));
        org.junit.jupiter.api.Assertions.assertTrue(
                ((InMemoryEdgeEventQueueRepository) queueRepository).findByEventId(second.eventId).orElseThrow().failNextReplay,
                "simulate replayFailure 应武装指定事件");
    }

    // ---------- 直接走 Service 的辅助（与 MockMvc 共享同一 Spring 上下文状态） ----------

    private void disconnect(String node) {
        opsService.simulateDisconnect(node);
    }

    private void createLocalEvent(String node, String type) {
        opsService.createLocalEvent(new EdgeOpsService.LocalEventRequest(node, type, null, null, null, null));
    }
}
