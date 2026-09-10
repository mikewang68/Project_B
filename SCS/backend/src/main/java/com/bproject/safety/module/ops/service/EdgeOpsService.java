package com.bproject.safety.module.ops.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.infrastructure.health.InfrastructureProbe;
import com.bproject.safety.infrastructure.health.ProbeResult;
import com.bproject.safety.module.alert.service.AlertMetrics;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgeLocalLinkage;
import com.bproject.safety.module.ops.model.EdgeNodeStatuses;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.model.RecoveryPhase;
import com.bproject.safety.module.ops.model.RecoveryPhases;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeNodeRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryOpsEventLogRepository;
import com.bproject.safety.module.ops.realtime.OpsLiveNotifier;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 运维监控 + 云边断网自治核心服务（SIMULATED EDGE AUTONOMY：同进程行为模拟，非真实边缘平台）。
 *
 * <p>职责边界（任务书第四节）：只读取现有 Rule / Fence / Alert 服务与仓储，不维护第二套业务数据；
 * 自身只维护节点状态、同步状态、离线队列、运行指标。核心验收：断网期间本地判定 / 联动继续，
 * 事件入离线队列但不进云端 Alert、不广播 alert.new；恢复时按固定五阶段对账后顺序幂等补传。</p>
 */
@Service
public class EdgeOpsService {

    private static final Logger log = LoggerFactory.getLogger(EdgeOpsService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final DateTimeFormatter EVT_TS = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    /** 校时后回到的合理偏差（任务书第十九节示例 +120ms）。 */
    private static final long CLOCK_OK_OFFSET_MS = 120L;
    private static final int TREND_POINTS = 30;

    private final InMemoryEdgeNodeRepository nodeRepository;
    private final EdgeEventQueueRepository queueRepository;
    private final InMemoryOpsEventLogRepository logRepository;
    private final OpsInventory inventory;
    private final EdgeReplayService replayService;
    private final OpsLiveNotifier notifier;
    private final RuleRepository ruleRepository;
    private final AlertService alertService;
    private final List<InfrastructureProbe> probes;
    private final Clock clock;

    public EdgeOpsService(InMemoryEdgeNodeRepository nodeRepository,
                          EdgeEventQueueRepository queueRepository,
                          InMemoryOpsEventLogRepository logRepository,
                          OpsInventory inventory, EdgeReplayService replayService,
                          OpsLiveNotifier notifier, RuleRepository ruleRepository,
                          AlertService alertService,
                          List<InfrastructureProbe> probes, Clock clock) {
        this.nodeRepository = nodeRepository;
        this.queueRepository = queueRepository;
        this.logRepository = logRepository;
        this.inventory = inventory;
        this.replayService = replayService;
        this.notifier = notifier;
        this.ruleRepository = ruleRepository;
        this.alertService = alertService;
        this.probes = probes;
        this.clock = clock;
    }

    // ==================== 查询：总览 / 拓扑 / 节点 ====================

    /** 运维总览聚合（任务书第八节，全部从当前仓储实时计算，不写死数字）。 */
    public Map<String, Object> overview() {
        List<DemoEdgeNode> nodes = listNodes();
        long online = nodes.stream().filter(n -> EdgeNodeStatuses.ONLINE.equals(n.status)).count();
        long offline = nodes.stream().filter(n -> EdgeNodeStatuses.OFFLINE.equals(n.status)).count();
        long degraded = nodes.stream().filter(n -> EdgeNodeStatuses.DEGRADED.equals(n.status)
                || EdgeNodeStatuses.RECOVERING.equals(n.status) || EdgeNodeStatuses.ERROR.equals(n.status)).count();
        List<EdgePendingEvent> all = queueRepository.findAll();
        long pending = all.stream().filter(e -> PendingEventStatuses.pendingLike(e.status)).count();
        long failed = all.stream().filter(e -> PendingEventStatuses.FAILED.equals(e.status)).count();
        long ruleMismatch = nodes.stream().filter(DemoEdgeNode::ruleMismatch).count();
        long clockDrift = nodes.stream().filter(DemoEdgeNode::clockDrift).count();

        List<OpsInventory.OpsInterface> interfaces = inventory.interfaces();
        long apiHealthy = interfaces.stream().filter(i -> "normal".equals(i.status())).count();
        long apiDegraded = interfaces.size() - apiHealthy;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("environment", "DEV");
        data.put("simulatedEdgeAutonomy", true);
        data.put("edgeNodesTotal", nodes.size());
        data.put("edgeNodesOnline", online);
        data.put("edgeNodesOffline", offline);
        data.put("degradedNodes", degraded);
        data.put("pendingSyncEvents", pending);
        data.put("failedSyncEvents", failed);
        data.put("ruleMismatchNodes", ruleMismatch);
        data.put("clockDriftNodes", clockDrift);
        data.put("apiHealthy", apiHealthy);
        data.put("apiDegraded", apiDegraded);
        data.put("alertPipelineStatus", alertPipelineStatus());
        data.put("realtimeStatus", realtimeStatus());
        data.put("components", healthComponents());
        data.put("cloudLink", link());
        data.put("lastUpdated", now().toString());
        return data;
    }

    /** 云边拓扑：平台 → 节点 → 节点下设备数量与链路状态。 */
    public Map<String, Object> topology() {
        List<DemoEdgeNode> nodes = listNodes();
        List<Map<String, Object>> nodeViews = new ArrayList<>();
        for (DemoEdgeNode n : nodes) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("id", n.id);
            v.put("name", n.name);
            v.put("area", n.area);
            v.put("status", n.status);
            v.put("statusLabel", EdgeNodeStatuses.chineseLabel(n.status));
            v.put("cloudConnected", n.cloudConnected);
            v.put("autonomyActive", n.autonomyActive);
            v.put("latencyMs", n.latencyMs);
            v.put("queueDepth", n.queueDepth);
            v.put("activeRuleVersion", n.activeRuleVersion);
            v.put("expectedRuleVersion", n.expectedRuleVersion);
            v.put("clockOffsetMs", n.clockOffsetMs);
            long deviceCount = inventory.devices().stream().filter(d -> n.id.equals(d.edgeNodeId())).count();
            v.put("deviceCount", deviceCount);
            nodeViews.add(v);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("simulatedEdgeAutonomy", true);
        data.put("cloud", Map.of("name", "智慧安全平台（Cloud）", "status", "online"));
        data.put("nodes", nodeViews);
        data.put("link", link());
        return data;
    }

    /** 节点列表：惰性刷新在线节点心跳与指标，并实时回填期望版本 / 队列深度。 */
    public List<DemoEdgeNode> listNodes() {
        List<DemoEdgeNode> snapshot = nodeRepository.findAll();
        List<DemoEdgeNode> result = new ArrayList<>();
        for (DemoEdgeNode copy : snapshot) {
            DemoEdgeNode mutable = nodeRepository.findMutable(copy.id).orElseThrow();
            refreshRuntime(mutable);
            result.add(mutable.copy());
        }
        return result;
    }

    public DemoEdgeNode getNode(String id) {
        DemoEdgeNode node = mutable(id);
        refreshRuntime(node);
        DemoEdgeNode view = node.copy();
        return view;
    }

    /** 节点详情附带趋势样本（按请求确定性生成，不建后台采样线程，任务书第四十七 / 四十八节）。 */
    public Map<String, Object> nodeDetail(String id) {
        DemoEdgeNode node = getNode(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("node", node);
        data.put("trend", Map.of(
                "cpu", trend(node, "cpu"),
                "memory", trend(node, "memory"),
                "latency", trend(node, "latency"),
                "queueDepth", trend(node, "queue")));
        data.put("localEvents", queueRepository.query(id, null));
        return data;
    }

    /** 在线节点惰性刷新心跳与运行指标（离线 / 恢复中冻结心跳，体现断网）。 */
    private void refreshRuntime(DemoEdgeNode n) {
        boolean dirty = false;
        if (EdgeNodeStatuses.ONLINE.equals(n.status) || EdgeNodeStatuses.DEGRADED.equals(n.status)) {
            OffsetDateTime cur = now();
            n.lastHeartbeat = cur;
            n.uptimeSec += 3;
            n.cpuUsage = jitter(n.id, 1, 28, 58);
            n.memoryUsage = jitter(n.id, 2, 38, 70);
            n.latencyMs = jitter(n.id, 3, 24, 130);
            n.temperature = jitter(n.id, 4, 24, 46);
            dirty = true;
        }
        String expectedRule = expectedRuleVersion();
        if (expectedRule != null && !expectedRule.equals(n.expectedRuleVersion)) {
            n.expectedRuleVersion = expectedRule;
            dirty = true;
        }
        int q = queueRepository.pendingCount(n.id);
        if (q != n.queueDepth) {
            n.queueDepth = q;
            dirty = true;
        }
        if (dirty) {
            nodeRepository.save(n);
        }
    }

    // ==================== 设备 / 接口 / 日志 ====================

    public List<OpsInventory.OpsDevice> devices() {
        return inventory.devices();
    }

    public OpsInventory.OpsDevice reconnectDevice(String deviceId) {
        OpsInventory.OpsDevice device = inventory.device(deviceId)
                .orElseThrow(() -> ApiException.notFound("设备不存在: " + deviceId));
        OpsInventory.OpsDevice updated = device.withStatus(OpsInventory.ONLINE, null, now());
        inventory.saveDevice(updated);
        appendLog(device.edgeNodeId(), "info", OpsEventLog.DEVICE_FAULT, "设备 " + deviceId + " 已重新连接");
        return updated;
    }

    public List<OpsInventory.OpsInterface> interfaces() {
        return inventory.interfaces();
    }

    public List<OpsEventLog> events(Integer limit, String nodeId, String level, String type,
                                    String from, String to) {
        return logRepository.recent(limit, nodeId, level, type, from, to);
    }

    // ==================== 云边链路状态 ====================

    /** 云边链路聚合状态（对齐前端 CloudLink：online / recovering / link-error / disconnected）。 */
    public Map<String, Object> link() {
        List<DemoEdgeNode> nodes = nodeRepository.findAll();
        boolean anyOffline = nodes.stream().anyMatch(n -> !n.cloudConnected);
        boolean anyRecovering = nodes.stream().anyMatch(n -> EdgeNodeStatuses.RECOVERING.equals(n.status));
        String state;
        String label;
        if (anyRecovering) {
            state = "recovering";
            label = "链路恢复中";
        } else if (anyOffline) {
            state = "disconnected";
            label = "云连接中断 · 边缘自治运行";
        } else {
            boolean drift = nodes.stream().anyMatch(DemoEdgeNode::clockDrift);
            state = drift ? "link-error" : "online";
            label = drift ? "链路质量异常" : "云边链路正常";
        }
        List<Map<String, Object>> nodeStates = nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.id);
            m.put("cloudConnected", n.cloudConnected);
            m.put("autonomyActive", n.autonomyActive);
            m.put("status", n.status);
            return m;
        }).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state);
        data.put("label", label);
        data.put("simulated", true);
        data.put("nodes", nodeStates);
        data.put("updatedAt", now().toString());
        return data;
    }

    // ==================== 模拟断网 / 恢复（任务十三、十七） ====================

    /** 模拟断网：cloudConnected=false、status=OFFLINE、心跳冻结，但 autonomy=ACTIVE。 */
    public DemoEdgeNode simulateDisconnect(String nodeId) {
        DemoEdgeNode n = mutable(nodeId);
        if (!n.cloudConnected) {
            throw ApiException.conflict("节点已处于断网状态: " + nodeId);
        }
        n.cloudConnected = false;
        n.autonomyActive = true;
        n.status = EdgeNodeStatuses.OFFLINE;
        n.latencyMs = null;
        n.lastError = "CLOUD_LINK_LOST";
        // 演示语义：节点断网前恰好未完成最新一版规则同步，因此边缘冻结在上一版（与 RuleService
        // simulateMismatch 的“EDGE-03 落后一版”口径一致）；历史事件 ruleVersionUsed 不回改。
        n.activeRuleVersion = previousMinor(n.expectedRuleVersion);
        n.activeFenceVersion = previousMinor(n.expectedFenceVersion);
        n.recoveryPhases = new ArrayList<>();
        n.recoveryPhase = null;
        nodeRepository.save(n);
        appendLog(nodeId, "error", OpsEventLog.NODE_OFFLINE,
                n.id + " 云连接中断，边缘自治激活：本地判定与本地联动继续，事件进入离线缓存");
        notifier.nodeChanged(n, "CLOUD_LINK_LOST");
        log.info("模拟断网 node={} activeRule={}", nodeId, n.activeRuleVersion);
        return n.copy();
    }

    /**
     * 启动 / 推进恢复流程（任务书第十七、十八节固定阶段）。可重复调用：
     * 遇到时钟 / 规则对账阻塞或补传失败时停在对应阶段，再次调用即继续推进（兼作 Retry）。
     */
    public synchronized DemoEdgeNode startRecovery(String nodeId) {
        DemoEdgeNode n = mutable(nodeId);
        if (EdgeNodeStatuses.ONLINE.equals(n.status)) {
            throw ApiException.conflict("节点已在线，无需恢复: " + nodeId);
        }
        if (n.recoveryPhases == null || n.recoveryPhases.isEmpty()) {
            n.status = EdgeNodeStatuses.RECOVERING;
            n.recoveryPhases = RecoveryPhases.newTimeline();
            n.recoveryPhase = RecoveryPhases.CONNECTIVITY;
            appendLog(nodeId, "info", OpsEventLog.NODE_RECONNECT, n.id + " 开始恢复云边链路");
        }
        advance(n);
        nodeRepository.save(n);
        return n.copy();
    }

    /** 恢复状态机推进：按固定顺序逐阶段执行，阻塞点等待对应对账接口。 */
    private void advance(DemoEdgeNode n) {
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            String phase = n.recoveryPhase;
            if (phase == null) {
                return;
            }
            switch (phase) {
                case RecoveryPhases.CONNECTIVITY -> {
                    phaseStart(n, phase);
                    n.cloudConnected = true;
                    n.latencyMs = jitter(n.id, 3, 30, 120);
                    phaseDone(n, phase, "云边链路已恢复");
                    notifier.recoveryChanged(n, RecoveryPhases.CLOCK);
                    n.recoveryPhase = RecoveryPhases.CLOCK;
                    progressed = true;
                }
                case RecoveryPhases.CLOCK -> {
                    phaseStart(n, phase);
                    if (n.clockDrift()) {
                        findPhase(n, phase).block("检测到时钟偏差 " + n.clockOffsetMs
                                + "ms，超过阈值 " + DemoEdgeNode.CLOCK_DRIFT_THRESHOLD_MS + "ms，等待时间对账");
                        appendLog(n.id, "warning", OpsEventLog.CLOCK_MISMATCH,
                                n.id + " 时钟偏差 " + n.clockOffsetMs + "ms，需时间对账");
                        notifier.syncChanged(n);
                        return;
                    }
                    phaseDone(n, phase, "时钟偏差在允许范围内（" + n.clockOffsetMs + "ms）");
                    notifier.recoveryChanged(n, RecoveryPhases.RULE);
                    n.recoveryPhase = RecoveryPhases.RULE;
                    progressed = true;
                }
                case RecoveryPhases.RULE -> {
                    phaseStart(n, phase);
                    n.expectedRuleVersion = expectedRuleVersion();
                    boolean mismatch = n.ruleMismatch();
                    boolean fenceMismatch = n.activeFenceVersion != null && n.expectedFenceVersion != null
                            && !n.activeFenceVersion.equals(n.expectedFenceVersion);
                    if (mismatch || fenceMismatch) {
                        findPhase(n, phase).block("规则版本不一致（边缘 " + n.activeRuleVersion
                                + " / 平台 " + n.expectedRuleVersion + "），等待版本对账");
                        appendLog(n.id, "warning", OpsEventLog.RULE_MISMATCH,
                                n.id + " 规则版本不一致：边缘 " + n.activeRuleVersion + "，平台 "
                                        + n.expectedRuleVersion);
                        notifier.syncChanged(n);
                        return;
                    }
                    phaseDone(n, phase, "规则 / 围栏版本与平台一致（" + n.activeRuleVersion + "）");
                    notifier.recoveryChanged(n, RecoveryPhases.EVENT_REPLAY);
                    n.recoveryPhase = RecoveryPhases.EVENT_REPLAY;
                    progressed = true;
                }
                case RecoveryPhases.EVENT_REPLAY -> {
                    phaseStart(n, phase);
                    List<EdgeReplayService.ReplayResult> results = replayService.replayPending(n);
                    n.queueDepth = queueRepository.pendingCount(n.id);
                    boolean hasFailure = results.stream()
                            .anyMatch(r -> PendingEventStatuses.FAILED.equals(r.status()));
                    if (hasFailure) {
                        findPhase(n, phase).block("存在补传失败事件，等待重试");
                        notifier.recoveryChanged(n, phase);
                        return;
                    }
                    phaseDone(n, phase, "离线事件补传完成（" + results.size() + " 条）");
                    notifier.recoveryChanged(n, RecoveryPhases.FINAL_CHECK);
                    n.recoveryPhase = RecoveryPhases.FINAL_CHECK;
                    progressed = true;
                }
                case RecoveryPhases.FINAL_CHECK -> {
                    phaseStart(n, phase);
                    if (queueRepository.pendingCount(n.id) > 0) {
                        findPhase(n, phase).block("离线队列尚未清空");
                        return;
                    }
                    phaseDone(n, phase, "队列已清空，节点状态一致");
                    finishRecovery(n);
                    return;
                }
                default -> {
                    return;
                }
            }
        }
    }

    /** 全部阶段完成：节点恢复 ONLINE、自治退出。 */
    private void finishRecovery(DemoEdgeNode n) {
        RecoveryPhase online = new RecoveryPhase(RecoveryPhases.ONLINE, RecoveryPhases.label(RecoveryPhases.ONLINE));
        online.start(now()).complete(now(), "节点恢复在线");
        n.recoveryPhases.add(online);
        n.recoveryPhase = null;
        n.status = EdgeNodeStatuses.ONLINE;
        n.autonomyActive = false;
        n.lastError = null;
        n.lastSyncAt = now();
        n.queueDepth = 0;
        appendLog(n.id, "info", OpsEventLog.NODE_ONLINE, n.id + " 恢复完成：时间 / 规则对账通过，离线事件已补传，节点在线");
        notifier.nodeChanged(n, null);
        log.info("节点恢复在线 node={}", n.id);
    }

    /** 时间对账（任务书第十九节，Demo 校时，不实现真实 NTP）。 */
    public DemoEdgeNode reconcileTime(String nodeId) {
        DemoEdgeNode n = mutable(nodeId);
        Long before = n.clockOffsetMs;
        n.clockOffsetMs = CLOCK_OK_OFFSET_MS;
        completeBlockedPhase(n, RecoveryPhases.CLOCK,
                "时间对账完成：偏差由 " + before + "ms 校正为 " + CLOCK_OK_OFFSET_MS + "ms");
        appendLog(nodeId, "info", OpsEventLog.CLOCK_RECONCILED,
                n.id + " 时间对账完成，时钟偏差 " + before + "ms → " + CLOCK_OK_OFFSET_MS + "ms（Demo 校时，非真实 NTP）");
        notifier.syncChanged(n);
        // 在线节点的时钟漂移会被标记 DEGRADED，校时后恢复 ONLINE（恢复流程中的节点交给状态机推进）
        if (EdgeNodeStatuses.DEGRADED.equals(n.status)) {
            n.status = EdgeNodeStatuses.ONLINE;
            n.lastError = null;
        }
        advanceIfRecovering(n);
        nodeRepository.save(n);
        return n.copy();
    }

    /** 规则版本对账（任务书第二十节，redeliver 重新下发 / keep 本次放行）。 */
    public DemoEdgeNode reconcileRules(String nodeId, String action) {
        DemoEdgeNode n = mutable(nodeId);
        String before = n.activeRuleVersion;
        boolean redeliver = action == null || "redeliver".equalsIgnoreCase(action);
        if (redeliver) {
            n.activeRuleVersion = n.expectedRuleVersion = expectedRuleVersion();
            n.activeFenceVersion = n.expectedFenceVersion;
        }
        completeBlockedPhase(n, RecoveryPhases.RULE, redeliver
                ? "规则重新下发完成：" + before + " → " + n.activeRuleVersion
                : "本次保持边缘版本 " + before + "（运维确认放行，稍后同步）");
        appendLog(nodeId, "info", OpsEventLog.RULE_RECONCILED,
                n.id + " 规则版本对账完成（" + (redeliver ? "重新下发" : "保持边缘版本")
                        + "）：" + before + " → " + n.activeRuleVersion);
        notifier.syncChanged(n);
        advanceIfRecovering(n);
        nodeRepository.save(n);
        return n.copy();
    }

    public Map<String, Object> ruleReconcileView() {
        List<DemoEdgeNode> nodes = listNodes();
        List<Map<String, Object>> items = nodes.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("nodeId", n.id);
            m.put("edgeRuleVersion", n.activeRuleVersion);
            m.put("expectedRuleVersion", n.expectedRuleVersion);
            m.put("edgeFenceVersion", n.activeFenceVersion);
            m.put("expectedFenceVersion", n.expectedFenceVersion);
            m.put("ruleMismatch", n.ruleMismatch());
            return m;
        }).toList();
        return Map.of("expectedRuleVersion", expectedRuleVersion(), "nodes", items);
    }

    private void advanceIfRecovering(DemoEdgeNode n) {
        if (EdgeNodeStatuses.RECOVERING.equals(n.status)) {
            advance(n);
        }
    }

    private void completeBlockedPhase(DemoEdgeNode n, String phaseKey, String message) {
        RecoveryPhase phase = n.recoveryPhases == null ? null
                : n.recoveryPhases.stream().filter(p -> phaseKey.equals(p.key)).findFirst().orElse(null);
        if (phase == null) {
            // 在线节点维护动作（无恢复时间线）：只完成对账本身，不操作阶段
            return;
        }
        if (phase.startedAt == null) {
            phase.start(now());
        }
        phase.complete(now(), message);
    }

    private void phaseStart(DemoEdgeNode n, String phaseKey) {
        RecoveryPhase phase = findPhase(n, phaseKey);
        if (phase.startedAt == null) {
            phase.start(now());
            notifier.recoveryChanged(n, phaseKey);
        }
    }

    private void phaseDone(DemoEdgeNode n, String phaseKey, String message) {
        findPhase(n, phaseKey).complete(now(), message);
    }

    private RecoveryPhase findPhase(DemoEdgeNode n, String key) {
        return n.recoveryPhases.stream().filter(p -> key.equals(p.key)).findFirst()
                .orElseThrow(() -> new IllegalStateException("恢复阶段不存在: " + key));
    }

    // ==================== 离线本地事件（任务十四 ~ 十六节，核心验收） ====================

    public List<EdgePendingEvent> localEvents(String nodeId, String status) {
        return queueRepository.query(nodeId, status);
    }

    /**
     * 断网期间边缘本地产生风险事件：继续本地判定、继续本地联动，事件进入离线队列；
     * <b>不</b>调用 AlertService、<b>不</b>进入云端 AlertRepository、<b>不</b>广播 alert.new。
     */
    public synchronized EdgePendingEvent createLocalEvent(LocalEventRequest req) {
        String nodeId = req != null && req.nodeId() != null ? req.nodeId() : "EDGE-03";
        DemoEdgeNode n = mutable(nodeId);
        if (n.cloudConnected) {
            throw ApiException.conflict("节点云连接正常，风险事件应直接上报云端，不进入离线队列: " + nodeId);
        }
        OffsetDateTime edgeNow = now();
        int seq = (int) queueRepository.count() + 1;
        String eventType = req != null && req.eventType() != null ? req.eventType()
                : LOCAL_TEMPLATES.get((n.cachedEventCount) % LOCAL_TEMPLATES.size()).eventType();
        LocalTemplate tpl = templateOf(eventType);
        String risk = req != null && req.risk() != null ? req.risk() : tpl.risk();
        String title = req != null && req.title() != null ? req.title() : tpl.title();
        String detail = req != null && req.detail() != null ? req.detail() : tpl.detail();

        EdgePendingEvent e = new EdgePendingEvent();
        e.eventId = "EDGE-EVT-" + edgeNow.format(EVT_TS) + "-" + String.format("%02d", seq);
        e.edgeNodeId = nodeId;
        e.eventType = eventType;
        e.businessKey = nodeId + ":" + eventType + ":" + seq;
        e.edgeOccurredAt = edgeNow;
        e.receivedAt = edgeNow;
        e.idempotencyKey = nodeId + ":" + eventType + ":" + edgeNow.toEpochSecond() + ":" + seq;
        e.clockOffsetAtOccurrence = n.clockOffsetMs;
        // 审计语义：冻结使用断网边缘当前规则版本，恢复后平台升级也不回改（任务书第三十九节）
        e.ruleVersionUsed = n.activeRuleVersion;
        e.risk = risk;
        boolean needPlc = "严重".equals(risk) || "紧急".equals(risk);
        e.payloadSummary = new EdgePendingEvent.PayloadSummary(title, n.area, tpl.person(),
                tpl.fence(), tpl.device(), risk, e.businessKey, detail);
        List<String> actions = new ArrayList<>(tpl.actions());
        if (needPlc) {
            actions.add("PLC 停车指令已执行");
        }
        e.localLinkage = EdgeLocalLinkage.success(actions, needPlc);
        e.status = PendingEventStatuses.PENDING;
        // 故障补传演示：可指定该事件下一次补传先失败一次（PLATFORM_TEMPORARILY_UNAVAILABLE），再重试成功
        if (req != null && Boolean.TRUE.equals(req.failNextReplay())) {
            e.failNextReplay = true;
        }
        queueRepository.save(e);

        n.cachedEventCount += 1;
        n.queueDepth = queueRepository.pendingCount(nodeId);
        n.diskUsage = Math.min(95, n.diskUsage + 1);
        nodeRepository.save(n);

        appendLog(nodeId, "warning", OpsEventLog.LOCAL_JUDGEMENT,
                "【EDGE LOCAL】" + title + "；本地判定成功、本地联动成功，事件进入离线缓存（" + e.eventId + "）");
        notifier.queueChanged(n);
        log.info("断网期间边缘本地事件入队 node={} event={} type={}（未进入云端 Alert）",
                nodeId, e.eventId, eventType);
        return e.copy();
    }

    /** 本地风险事件模板（对齐前端 LOCAL_RISK_POOL，SIMULATED）。 */
    private record LocalTemplate(String eventType, String risk, String title, String person,
                                 String fence, String device, String detail, List<String> actions) {
    }

    private static final List<LocalTemplate> LOCAL_TEMPLATES = List.of(
            new LocalTemplate("person-intrusion", "紧急", "人员进入危险围栏", "赵磊（P-1003）",
                    "FENCE-001 龙门吊动态禁区", null, "人员进入危险围栏，边缘本地判定命中",
                    List.of("现场声光报警", "边缘大屏弹窗", "本地语音提醒", "警示灯点亮")),
            new LocalTemplate("collision-risk", "严重", "车辆交汇碰撞风险", null, null,
                    "集卡 VEH-07 / 场桥 TIP-02", "最近距离 3.6m，边缘防碰撞判定命中",
                    List.of("现场声光报警", "边缘大屏弹窗", "本地语音提醒", "警示灯点亮")),
            new LocalTemplate("ppe-violation", "预警", "未佩戴安全帽", "孙鹏（P-1007）",
                    null, "EDGE-03 本地摄像头", "边缘 AI 识别未佩戴安全帽",
                    List.of("现场语音提醒", "边缘大屏提示")),
            new LocalTemplate("person-stay", "严重", "人员异常滞留", "周倩（P-1011）",
                    "FENCE-005 车辆盲区", null, "人员在车辆盲区滞留超过阈值",
                    List.of("现场声光报警", "边缘大屏弹窗", "本地语音提醒", "警示灯点亮")));

    private LocalTemplate templateOf(String eventType) {
        return LOCAL_TEMPLATES.stream().filter(t -> t.eventType().equals(eventType)).findFirst()
                .orElse(LOCAL_TEMPLATES.get(0));
    }

    /** 让某条离线事件下一次补传失败一次（任务书第三十五节故障补传演示）。 */
    public EdgePendingEvent armFailure(String eventId) {
        var mutable = ((InMemoryEdgeEventQueueRepository) queueRepository).findMutable(eventId)
                .orElseThrow(() -> ApiException.notFound("离线事件不存在: " + eventId));
        mutable.failNextReplay = true;
        queueRepository.save(mutable);
        return mutable.copy();
    }

    // ==================== 运维模拟场景 / 节点维护 ====================

    /** 运维页模拟场景：deviceFault 设备故障 / cacheAlert 缓存告警 / timeDrift 时钟漂移。 */
    public Map<String, Object> simulate(String scenario, String targetId) {
        return switch (scenario == null ? "" : scenario) {
            case "timeDrift" -> {
                DemoEdgeNode n = simulateTimeDrift(targetId == null ? "EDGE-02" : targetId, 3200L);
                yield Map.of("scenario", scenario, "node", n);
            }
            case "deviceFault" -> {
                OpsInventory.OpsDevice d = simulateDeviceFault(targetId);
                yield Map.of("scenario", scenario, "device", d);
            }
            case "cacheAlert" -> {
                DemoEdgeNode n = simulateCacheAlert(targetId == null ? "EDGE-03" : targetId);
                yield Map.of("scenario", scenario, "node", n);
            }
            case "replayFailure" -> {
                // targetId 为离线事件 eventId：令其下一次补传先失败一次（任务书第三十五节）
                EdgePendingEvent e = armFailure(targetId);
                yield Map.of("scenario", scenario, "event", e);
            }
            default -> throw ApiException.unprocessable("不支持的模拟场景: " + scenario);
        };
    }

    /** 时钟漂移（任务书第五十四节：EDGE-02 +3200ms）。 */
    public DemoEdgeNode simulateTimeDrift(String nodeId, long offsetMs) {
        DemoEdgeNode n = mutable(nodeId);
        n.clockOffsetMs = offsetMs;
        if (EdgeNodeStatuses.ONLINE.equals(n.status)) {
            n.status = EdgeNodeStatuses.DEGRADED;
        }
        n.lastError = "CLOCK_DRIFT";
        nodeRepository.save(n);
        appendLog(nodeId, "warning", OpsEventLog.CLOCK_MISMATCH,
                n.id + " 模拟时钟漂移至 +" + offsetMs + "ms，超过阈值，标记时间异常");
        notifier.nodeChanged(n, "CLOCK_DRIFT");
        return n.copy();
    }

    private OpsInventory.OpsDevice simulateDeviceFault(String deviceId) {
        String id = deviceId != null ? deviceId : "CAM-07";
        OpsInventory.OpsDevice d = inventory.device(id)
                .orElseThrow(() -> ApiException.notFound("设备不存在: " + id));
        OpsInventory.OpsDevice fault = d.withStatus(OpsInventory.FAULT, "设备无响应（模拟故障）", now());
        inventory.saveDevice(fault);
        appendLog(d.edgeNodeId(), "error", OpsEventLog.DEVICE_FAULT, "设备 " + id + " 发生故障：设备无响应（模拟）");
        return fault;
    }

    private DemoEdgeNode simulateCacheAlert(String nodeId) {
        DemoEdgeNode n = mutable(nodeId);
        n.diskUsage = Math.min(96, n.diskUsage + 12);
        n.cacheParts = List.of(
                new DemoEdgeNode.CachePart("事件缓存", Math.min(99, n.diskUsage + 6)),
                new DemoEdgeNode.CachePart("视频证据缓存", Math.min(99, n.diskUsage + 11)),
                new DemoEdgeNode.CachePart("日志空间", Math.min(99, n.diskUsage - 2)));
        if (EdgeNodeStatuses.ONLINE.equals(n.status)) {
            n.status = EdgeNodeStatuses.DEGRADED;
        }
        nodeRepository.save(n);
        appendLog(nodeId, "warning", OpsEventLog.CACHE_WARNING, n.id + " 边缘缓存占用升高至 " + n.diskUsage + "%（模拟）");
        notifier.nodeChanged(n, "CACHE_WARNING");
        return n.copy();
    }

    /** 节点抽屉维护动作：reconnect 发起恢复 / resyncTime 校时 / redeliverRule 重下发规则。 */
    public DemoEdgeNode maintain(String nodeId, String action) {
        DemoEdgeNode n = mutable(nodeId);
        return switch (action == null ? "" : action) {
            case "reconnect" -> startRecovery(nodeId);
            case "resyncTime" -> reconcileTime(nodeId);
            case "redeliverRule" -> reconcileRules(nodeId, "redeliver");
            default -> throw ApiException.unprocessable("不支持的维护动作: " + action);
        };
    }

    // ==================== 基础设施健康（复用现有探针，任务九、十节） ====================

    /**
     * 复用 ReadinessAggregator / InfrastructureProbe，不重写第二套探针。
     * 本地 openGauss / Kvrocks DOWN、RocketMQ / openGemini DISABLED 都是合法状态，
     * capabilityState 区分 UP / DEGRADED / UNAVAILABLE / DISABLED，页面不白屏、不全标红。
     */
    public List<Map<String, Object>> healthComponents() {
        Map<String, ProbeResult> probed = new LinkedHashMap<>();
        for (InfrastructureProbe probe : probes) {
            try {
                probed.put(probe.componentName(), probe.probe());
            } catch (RuntimeException ex) {
                probed.put(probe.componentName(), ProbeResult.down(ex.getMessage()));
            }
        }
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(component("Application", "应用服务", "UP", "normal", "Spring Boot 运行中"));
        list.add(component("Database", "openGauss 数据库", mapProbe(probed.get("database")), null,
                detail(probed.get("database"), "本地未连接 openGauss（Demo 合法状态）")));
        list.add(component("Cache", "Kvrocks 缓存", mapProbe(probed.get("cache")), null,
                detail(probed.get("cache"), "本地未连接 Kvrocks（Demo 合法状态）")));
        list.add(component("MQ", "RocketMQ 消息队列", mapProbe(probed.get("rocketmq")), null,
                detail(probed.get("rocketmq"), "本地未启用 RocketMQ，离线队列使用进程内缓存")));
        list.add(component("Timeseries", "openGemini 时序库", mapProbe(probed.get("openGemini")), null,
                detail(probed.get("openGemini"), "本地未启用 openGemini，趋势使用内存样本")));
        list.add(component("Realtime", "WebSocket 实时通道", "UP", "normal", "/ws/live 正常"));
        return list;
    }

    private Map<String, Object> component(String key, String name, String capability,
                                          String display, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", key);
        m.put("name", name);
        m.put("capabilityState", capability);
        m.put("state", display == null ? capability.toLowerCase() : display);
        m.put("message", message);
        return m;
    }

    /** Probe 状态 → capabilityState：UP / DISABLED / UNAVAILABLE。 */
    private String mapProbe(ProbeResult result) {
        if (result == null) {
            return "UNAVAILABLE";
        }
        return switch (result.status()) {
            case UP -> "UP";
            case DISABLED -> "DISABLED";
            case DOWN -> "UNAVAILABLE";
        };
    }

    private String detail(ProbeResult result, String fallback) {
        if (result == null) {
            return fallback;
        }
        return switch (result.status()) {
            case UP -> "已连接";
            case DISABLED -> "已禁用（本地 Demo 无需启用）";
            case DOWN -> fallback;
        };
    }

    private String alertPipelineStatus() {
        AlertMetrics m = alertService.metrics();
        return m.active() > 0 ? "运行中 · " + m.active() + " 起处置中" : "运行中";
    }

    private String realtimeStatus() {
        return "/ws/live 正常";
    }

    // ==================== 辅助 ====================

    private DemoEdgeNode mutable(String id) {
        return nodeRepository.findMutable(id)
                .orElseThrow(() -> ApiException.notFound("边缘节点不存在: " + id));
    }

    /** 平台最新已生效规则版本（读现有 RuleRepository，不建第二套规则库；取不到回退种子版本）。 */
    private String expectedRuleVersion() {
        try {
            return ruleRepository.findAll().stream()
                    .filter(r -> "RULE-PER-001".equals(r.id) && RuleStatuses.ACTIVE.equals(r.status))
                    .map(r -> r.version).findFirst()
                    .orElse(InMemoryEdgeNodeRepository.SEED_RULE_VERSION);
        } catch (RuntimeException ex) {
            return InMemoryEdgeNodeRepository.SEED_RULE_VERSION;
        }
    }

    /** v3.3 → v3.2（Demo：断网期间错过最新一版同步）。 */
    private String previousMinor(String version) {
        if (version == null) {
            return InMemoryEdgeNodeRepository.SEED_RULE_VERSION;
        }
        int idx = version.lastIndexOf('.');
        if (idx <= 1) {
            return version;
        }
        try {
            int minor = Integer.parseInt(version.substring(idx + 1));
            return version.substring(0, idx + 1) + Math.max(0, minor - 1);
        } catch (NumberFormatException ex) {
            return version;
        }
    }

    /** 确定性趋势样本（同一节点同一指标稳定复现，最后一点为当前值；不建后台线程）。 */
    private List<Integer> trend(DemoEdgeNode n, String metric) {
        int base = switch (metric) {
            case "cpu" -> n.cpuUsage == null ? 40 : n.cpuUsage;
            case "memory" -> n.memoryUsage == null ? 45 : n.memoryUsage;
            case "latency" -> n.latencyMs == null ? 60 : n.latencyMs;
            default -> n.queueDepth;
        };
        int seed = Math.abs((n.id + metric).hashCode());
        List<Integer> points = new ArrayList<>();
        for (int i = 0; i < TREND_POINTS - 1; i++) {
            int v = base - 6 + (seed >> (i % 16)) % 13;
            points.add(Math.max(0, v));
        }
        points.add(base);
        return points;
    }

    private int jitter(String id, int salt, int min, int max) {
        int h = Math.abs((id + "#" + salt + "#" + (now().getMinute() / 2)).hashCode());
        return min + h % (max - min);
    }

    private void appendLog(String nodeId, String level, String type, String text) {
        OffsetDateTime ts = now();
        logRepository.append(new OpsEventLog("OPS-LOG-" + System.nanoTime(), ts,
                ts.format(HMS), nodeId, level, type, text));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(ZONE));
    }

    /** createLocalEvent 入参（全部可空，缺省走轮转模板，默认 EDGE-03）。 */
    public record LocalEventRequest(String nodeId, String eventType, String risk, String title,
                                    String detail, Boolean failNextReplay) {
    }
}
