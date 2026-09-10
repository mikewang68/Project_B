package com.bproject.safety.module.ops.service;

import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryEdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.InMemoryOpsEventLogRepository;
import com.bproject.safety.module.ops.realtime.OpsLiveNotifier;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 离线事件幂等补传服务（任务书第二十二 ~ 二十六、三十五节）。
 *
 * <p>关键保证：</p>
 * <ul>
 *   <li>同一 eventId / idempotencyKey 云端只消费一次：进程内 processedKeys 兜底，
 *       已 SYNCED 的事件再次 replay 直接返回 DUPLICATE / alreadyProcessed，不再建 Alert；</li>
 *   <li>补传 Alert 的 occurredAt 取边缘发生时间，syncedAt 才是补传时刻，syncDelaySec 记录延迟；</li>
 *   <li>支持首次失败（PLATFORM_TEMPORARILY_UNAVAILABLE）→ FAILED → Retry 成功，不真正停掉服务；</li>
 *   <li>顺序补传：按 occurredAt、eventId 稳定排序，不做并发。</li>
 * </ul>
 */
@Service
public class EdgeReplayService {

    private static final Logger log = LoggerFactory.getLogger(EdgeReplayService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final Pattern DISTANCE = Pattern.compile("([0-9]+(?:\\.[0-9]+)?)\\s*m");

    /** 首次补传失败的演示错误码（任务书第三十五节）。 */
    public static final String ERR_PLATFORM_UNAVAILABLE = "PLATFORM_TEMPORARILY_UNAVAILABLE";

    private final EdgeEventQueueRepository queueRepository;
    private final InMemoryOpsEventLogRepository logRepository;
    private final AlertService alertService;
    private final OpsLiveNotifier notifier;
    private final Clock clock;

    /** Backend 幂等兜底：已成功消费的 eventId / idempotencyKey（不依赖前端不点第二次）。 */
    private final Set<String> processedEventIds = new HashSet<>();
    private final Set<String> processedIdemKeys = new HashSet<>();

    public EdgeReplayService(EdgeEventQueueRepository queueRepository,
                             InMemoryOpsEventLogRepository logRepository,
                             AlertService alertService, OpsLiveNotifier notifier, Clock clock) {
        this.queueRepository = queueRepository;
        this.logRepository = logRepository;
        this.alertService = alertService;
        this.notifier = notifier;
        this.clock = clock;
    }

    /** 补传结果。 */
    public record ReplayResult(String eventId, String status, String alertId, boolean alreadyProcessed,
                               String errorCode, Long syncDelaySec) {
    }

    /** 测试 / 重置用：清空幂等记忆与队列。 */
    public synchronized void reset() {
        processedEventIds.clear();
        processedIdemKeys.clear();
        queueRepository.clear();
        logRepository.clear();
    }

    /**
     * 补传单条事件（调用方保证节点处于恢复流程）。
     * 已 SYNCED / 已消费 → DUPLICATE；首次失败标记 → FAILED；否则建 Alert 并 SYNCED。
     */
    public synchronized ReplayResult replayOne(EdgePendingEvent event, DemoEdgeNode node) {
        String eventId = event.eventId;
        // 1) 幂等兜底：状态已是 SYNCED 或幂等键已消费 → DUPLICATE，不重复建 Alert
        if (PendingEventStatuses.SYNCED.equals(event.status)
                || processedEventIds.contains(eventId)
                || (event.idempotencyKey != null && processedIdemKeys.contains(event.idempotencyKey))) {
            markDuplicate(event, node, "重复补传被幂等去重");
            return new ReplayResult(eventId, PendingEventStatuses.DUPLICATE, event.linkedAlertId,
                    true, null, event.syncDelaySec);
        }

        // 2) 首次失败模拟（不真正停掉 Spring Boot）
        if (event.failNextReplay) {
            event.failNextReplay = false;
            event.status = PendingEventStatuses.FAILED;
            event.retryCount += 1;
            event.lastRetryAt = now();
            event.lastError = ERR_PLATFORM_UNAVAILABLE;
            queueRepository.save(event);
            append(node.id, "error", OpsEventLog.REPLAY_FAILED,
                    "事件 " + eventId + " 首次补传失败：" + ERR_PLATFORM_UNAVAILABLE + "，可重试");
            notifier.queueChanged(node);
            log.info("边缘事件首次补传失败（模拟）event={} retry={}", eventId, event.retryCount);
            return new ReplayResult(eventId, PendingEventStatuses.FAILED, null, false,
                    ERR_PLATFORM_UNAVAILABLE, null);
        }

        // 3) 正常补传：进入 SYNCING → 调现有 AlertService 建单（仍是同一条 Alert 主链）
        event.status = PendingEventStatuses.SYNCING;
        queueRepository.save(event);

        AlertService.EdgeReplayDraft draft = toDraft(event, node);
        DemoAlert alert = alertService.createEdgeReplayAlert(draft);

        OffsetDateTime syncedAt = now();
        event.status = PendingEventStatuses.SYNCED;
        event.linkedAlertId = alert.id;
        event.serverReceivedAt = syncedAt;
        event.syncedAt = syncedAt;
        event.syncDelaySec = Math.max(0, Duration.between(event.edgeOccurredAt, syncedAt).getSeconds());
        event.lastError = null;
        queueRepository.save(event);

        processedEventIds.add(eventId);
        if (event.idempotencyKey != null) {
            processedIdemKeys.add(event.idempotencyKey);
        }
        append(node.id, "info", OpsEventLog.REPLAY_SUCCESS,
                "事件 " + eventId + " 补传成功，生成告警 " + alert.id
                        + "（边缘发生 " + event.edgeOccurredAt.format(HMS) + "）");
        notifier.queueChanged(node);
        log.info("边缘事件补传成功 event={} alert={} delaySec={}", eventId, alert.id, event.syncDelaySec);
        return new ReplayResult(eventId, PendingEventStatuses.SYNCED, alert.id, false, null, event.syncDelaySec);
    }

    /** 对某节点队列中所有 PENDING / FAILED 事件按稳定顺序补传，返回逐条结果。 */
    public synchronized List<ReplayResult> replayPending(DemoEdgeNode node) {
        List<EdgePendingEvent> pending = queueRepository.query(node.id, null).stream()
                .filter(e -> PendingEventStatuses.PENDING.equals(e.status)
                        || PendingEventStatuses.FAILED.equals(e.status))
                .sorted(InMemoryEdgeEventQueueRepository.queueOrder())
                .toList();
        append(node.id, "info", OpsEventLog.REPLAY_STARTED, "开始顺序补传 " + pending.size() + " 条离线事件");
        return pending.stream().map(e -> replayOne(mutable(e.eventId), node)).toList();
    }

    private void markDuplicate(EdgePendingEvent event, DemoEdgeNode node, String text) {
        // 已 SYNCED 的保持 SYNCED + alreadyProcessed；其余（如重放 PENDING）标 DUPLICATE
        if (!PendingEventStatuses.SYNCED.equals(event.status)) {
            event.status = PendingEventStatuses.DUPLICATE;
            queueRepository.save(event);
        }
        append(node.id, "info", OpsEventLog.DUPLICATE_SKIPPED,
                text + "：" + event.eventId + "（原告警 " + event.linkedAlertId + "）");
        notifier.queueChanged(node);
    }

    /** 边缘事件 → Alert 补传草稿（按事件类型映射到现有风险口径，不建第二套业务数据）。 */
    private AlertService.EdgeReplayDraft toDraft(EdgePendingEvent e, DemoEdgeNode node) {
        EdgePendingEvent.PayloadSummary s = e.payloadSummary;
        String title = s != null && s.title != null ? s.title : e.eventType;
        String area = s != null && s.area != null ? s.area : node.area;
        String target = s != null ? firstNonBlank(s.person, s.device, s.businessId) : e.businessKey;
        String source;
        String eventType;
        AlertEvidence evidence;
        String judgement;
        String linkageText = e.localLinkage != null && e.localLinkage.actions != null
                ? String.join("、", e.localLinkage.actions) : "现场联动";
        switch (e.eventType) {
            case "collision-risk" -> {
                source = "设备防碰撞";
                eventType = "设备交汇风险";
                double dist = parseDistance(s == null ? null : s.detail);
                evidence = AlertEvidence.CollisionEvidence.of(dist, 0, List.of(), "边缘雷达 · 本地可用", "Demo");
                judgement = "边缘防碰撞规则命中，最近距离 " + dist + "m";
            }
            case "ppe-violation" -> {
                source = "AI违规";
                eventType = "未佩戴安全帽";
                evidence = AlertEvidence.AiEvidence.of(area, List.of(), 0.91, "PPE-Detection-edge",
                        node.id + " 边缘摄像头", e.edgeOccurredAt.format(HMS));
                judgement = "边缘 AI 识别未佩戴安全帽";
            }
            case "person-stay" -> {
                source = "人员安全";
                eventType = "人员滞留";
                evidence = AlertEvidence.PersonnelEvidence.of(List.of(), area,
                        s == null ? null : s.fence, "边缘手环 · 本地可用", "在线", "—");
                judgement = "边缘围栏规则命中：人员异常滞留";
            }
            default -> {
                source = "人员安全";
                eventType = "人员越界";
                evidence = AlertEvidence.PersonnelEvidence.of(List.of(), area,
                        s == null ? null : s.fence, "边缘手环 · 本地可用", "在线 · 本地震动提醒", "—");
                judgement = "Point-In-Polygon 边缘本地判定命中危险围栏";
            }
        }
        String ruleId = "collision-risk".equals(e.eventType) ? "RULE-DEV-003" : "RULE-PER-001";
        return new AlertService.EdgeReplayDraft(node.id, e.eventId, e.edgeOccurredAt, source, title,
                eventType, e.risk, area, target, ruleId, e.ruleVersionUsed, 0, evidence, judgement, linkageText);
    }

    private double parseDistance(String detail) {
        if (detail == null) {
            return 3.6;
        }
        Matcher m = DISTANCE.matcher(detail);
        return m.find() ? Double.parseDouble(m.group(1)) : 3.6;
    }

    private EdgePendingEvent mutable(String eventId) {
        Optional<EdgePendingEvent> found =
                ((InMemoryEdgeEventQueueRepository) queueRepository).findMutable(eventId);
        return found.orElseThrow(() -> new IllegalStateException("离线事件不存在: " + eventId));
    }

    private void append(String nodeId, String level, String type, String text) {
        OffsetDateTime now = now();
        String id = "OPS-LOG-" + System.nanoTime();
        logRepository.append(new OpsEventLog(id, now, now.format(HMS), nodeId, level, type, text));
    }

    private OffsetDateTime now() {
        return OffsetDateTime.now(clock.withZone(ZONE));
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }
}
