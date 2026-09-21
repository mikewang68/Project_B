package com.bproject.safety.module.ops.service;

import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.ops.repository.OpsEventLogRepository;
import com.bproject.safety.module.ops.realtime.OpsLiveNotifier;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
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
 *   <li>同一 eventId / idempotencyKey 云端只消费一次：<b>判重权威来自 {@link EdgeEventQueueRepository}</b>
 *       （事件状态 SYNCED / DUPLICATE 与 idempotencyKey 查询），不再使用 Service 进程内 HashSet，
 *       服务重建后判重依然有效；未来由 openGauss UNIQUE(idempotency_key) 兜底；</li>
 *   <li>补传 Alert 的 occurredAt 取边缘发生时间，syncedAt 才是补传时刻，syncDelaySec 记录延迟；</li>
 *   <li>支持首次失败（PLATFORM_TEMPORARILY_UNAVAILABLE）→ FAILED → Retry 成功，不真正停掉服务；</li>
 *   <li>顺序补传：{@link EdgeEventQueueRepository#findPendingForReplay(String)} 按 occurredAt、eventId
 *       稳定排序，不做并发；</li>
 *   <li>Phase B：建单 / 事件回写 / 日志全部 save 成功后才经 {@link LiveEventGate} 统一广播。</li>
 * </ul>
 *
 * <p>注：同 idempotencyKey 但 payload 不同的冲突检测（CONFLICT）留待 openGauss 阶段以唯一约束实现，
 * 本阶段同键已消费即判 DUPLICATE。</p>
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
    private final OpsEventLogRepository logRepository;
    private final AlertService alertService;
    private final OpsLiveNotifier notifier;
    private final Clock clock;
    private final LiveEventGate gate;

    public EdgeReplayService(EdgeEventQueueRepository queueRepository,
                             OpsEventLogRepository logRepository,
                             AlertService alertService, OpsLiveNotifier notifier, Clock clock,
                             LiveEventGate gate) {
        this.queueRepository = queueRepository;
        this.logRepository = logRepository;
        this.alertService = alertService;
        this.notifier = notifier;
        this.clock = clock;
        this.gate = gate;
    }

    /** 补传结果。 */
    public record ReplayResult(String eventId, String status, String alertId, boolean alreadyProcessed,
                               String errorCode, Long syncDelaySec) {
    }

    /**
     * 补传单条事件（调用方保证节点处于恢复流程）。
     * 已 SYNCED / 已消费 → DUPLICATE；首次失败标记 → FAILED；否则建 Alert 并 SYNCED。
     */
    public synchronized ReplayResult replayOne(EdgePendingEvent incoming, DemoEdgeNode node) {
        // 以仓储中的事件状态为权威（入参可能是查询副本）。
        EdgePendingEvent event = queueRepository.findByEventId(incoming.eventId).orElse(incoming);
        String eventId = event.eventId;

        // 1) 幂等判重（权威来自仓储，不使用进程内 Set）：
        //    事件自身已 SYNCED/DUPLICATE，或同 idempotencyKey 的另一事件已消费 → DUPLICATE
        EdgePendingEvent keyHit = event.idempotencyKey == null ? null
                : queueRepository.findByIdempotencyKey(event.idempotencyKey).orElse(null);
        boolean selfConsumed = PendingEventStatuses.SYNCED.equals(event.status)
                || PendingEventStatuses.DUPLICATE.equals(event.status);
        boolean keyConsumed = keyHit != null && !keyHit.eventId.equals(eventId)
                && (PendingEventStatuses.SYNCED.equals(keyHit.status)
                        || PendingEventStatuses.DUPLICATE.equals(keyHit.status));
        if (selfConsumed || keyConsumed) {
            String referenceAlertId = selfConsumed ? event.linkedAlertId : keyHit.linkedAlertId;
            markDuplicate(event, node, "重复补传被幂等去重");
            return new ReplayResult(eventId, PendingEventStatuses.DUPLICATE, referenceAlertId,
                    true, null, event.syncDelaySec);
        }

        // 2) 首次失败模拟（不真正停掉 Spring Boot）
        if (event.failNextReplay) {
            return gate.buffer(() -> {
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
            });
        }

        // 3) 正常补传：SYNCING → 建 Alert → SYNCED 回写；全部 save 成功后再统一广播。
        return gate.buffer(() -> {
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

            append(node.id, "info", OpsEventLog.REPLAY_SUCCESS,
                    "事件 " + eventId + " 补传成功，生成告警 " + alert.id
                            + "（边缘发生 " + event.edgeOccurredAt.format(HMS) + "）");
            notifier.queueChanged(node);
            log.info("边缘事件补传成功 event={} alert={} delaySec={}", eventId, alert.id, event.syncDelaySec);
            return new ReplayResult(eventId, PendingEventStatuses.SYNCED, alert.id, false, null,
                    event.syncDelaySec);
        });
    }

    /** 对某节点队列中所有 PENDING / FAILED 事件按稳定顺序补传，返回逐条结果（逐条提交 / 广播）。 */
    public synchronized List<ReplayResult> replayPending(DemoEdgeNode node) {
        List<EdgePendingEvent> pending = queueRepository.findPendingForReplay(node.id);
        append(node.id, "info", OpsEventLog.REPLAY_STARTED, "开始顺序补传 " + pending.size() + " 条离线事件");
        return pending.stream().map(e -> replayOne(e, node)).toList();
    }

    private void markDuplicate(EdgePendingEvent event, DemoEdgeNode node, String text) {
        gate.buffer(() -> {
            // 已 SYNCED 的保持 SYNCED + alreadyProcessed；其余（如重放 PENDING）标 DUPLICATE
            if (!PendingEventStatuses.SYNCED.equals(event.status)) {
                event.status = PendingEventStatuses.DUPLICATE;
                queueRepository.save(event);
            }
            append(node.id, "info", OpsEventLog.DUPLICATE_SKIPPED,
                    text + "：" + event.eventId + "（原告警 " + event.linkedAlertId + "）");
            notifier.queueChanged(node);
        });
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
        // 边缘补传 provenance 按事件真实判定来源标记，不再把围栏 / 模型版本伪装成 SafetyRule（F-07）。
        String riskCode = com.bproject.safety.module.alert.model.RiskLevels.normalize(e.risk);
        if (riskCode == null) {
            riskCode = com.bproject.safety.module.alert.model.RiskLevels.WARNING;
        }
        String sourceType;
        String sourceCode = null;
        String sourceVersion = null;
        switch (e.eventType) {
            case "collision-risk" -> sourceType = com.bproject.safety.module.alert.model.DecisionSources.COLLISION;
            case "ppe-violation" -> {
                sourceType = com.bproject.safety.module.alert.model.DecisionSources.AI_MODEL;
                sourceCode = "PPE-Detection-edge";
            }
            default -> {
                // 人员滞留 / 越界由边缘围栏空间判定产生
                sourceType = com.bproject.safety.module.alert.model.DecisionSources.FENCE;
                sourceCode = s == null ? null : s.fence;
                sourceVersion = e.ruleVersionUsed;
            }
        }
        return new AlertService.EdgeReplayDraft(node.id, e.eventId, e.edgeOccurredAt, source, title,
                eventType, riskCode, area, target, null, null,
                sourceType, sourceCode, sourceVersion, 0, evidence, judgement, linkageText);
    }

    private double parseDistance(String detail) {
        if (detail == null) {
            return 3.6;
        }
        Matcher m = DISTANCE.matcher(detail);
        return m.find() ? Double.parseDouble(m.group(1)) : 3.6;
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
