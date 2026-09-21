package com.bproject.safety.module.alert.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.EscalateRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.LinkageRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ReviewRejectRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ReviewRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TakeoverRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TreatmentRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TransferRequest;
import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.PersonnelEvidence;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.MobileStages;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.TreatmentRecord;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.AlertQuery;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoUser;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.UnaryOperator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 告警中心业务服务（Backend Demo）：状态流转、时间线追加、SLA 计算、指标聚合、幂等包装。
 *
 * <p>落库前语义收口：内部状态 / 风险判断一律使用机器 code（{@link AlertStatuses} /
 * {@link RiskLevels}），中文仅作为响应展示标签由模型 getter 派生；派单责任人以 USR-xxx code
 * 为权威关联、姓名为快照；时间线节点携带 eventType / sequenceNo。</p>
 *
 * <p>主链路：PENDING_CONFIRM →(confirm) PENDING_ASSIGNMENT →(assign) PENDING_PROCESS
 * →(start) PROCESSING →(treatment) PENDING_REVIEW →(review) CLOSED；非法流转抛 409。</p>
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String STATE_MSG = "当前状态不允许执行该操作";
    private static final String UNASSIGNED = "待分配";

    private final AlertRepository repository;
    private final IdempotencyService idempotency;
    private final DemoUserProperties demoUser;
    private final DemoMasterData masterData;
    private final Clock clock;
    private final AlertChangeNotifier notifier;
    private final AlertNumberGenerator numberGenerator;

    public AlertService(AlertRepository repository, IdempotencyService idempotency,
                        DemoUserProperties demoUser, DemoMasterData masterData, Clock clock,
                        AlertChangeNotifier notifier, AlertNumberGenerator numberGenerator) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.demoUser = demoUser;
        this.masterData = masterData;
        this.clock = clock;
        this.notifier = notifier == null ? AlertChangeNotifier.NOOP : notifier;
        this.numberGenerator = numberGenerator;
    }

    // ---------- 查询 ----------

    public AlertPageResult page(AlertQuery query) {
        AlertPageResult result = repository.page(query);
        result.list().forEach(this::refreshSla);
        return result;
    }

    public DemoAlert get(String id) {
        DemoAlert alert = requireAlert(id);
        refreshSla(alert);
        return alert;
    }

    public AlertMetrics metrics() {
        List<DemoAlert> all = repository.findAll();
        long pending = all.stream().filter(a -> AlertStatuses.PENDING_CONFIRM.equals(a.statusCode)).count();
        long active = all.stream().filter(a -> AlertStatuses.ACTIVE.contains(a.statusCode)).count();
        long severe = all.stream()
                .filter(a -> RiskLevels.SEVERE.equals(a.riskCode) && !AlertStatuses.CLOSED.equals(a.statusCode)).count();
        long urgent = all.stream()
                .filter(a -> RiskLevels.URGENT.equals(a.riskCode) && !AlertStatuses.CLOSED.equals(a.statusCode)).count();
        long closed = all.stream().filter(a -> AlertStatuses.CLOSED.equals(a.statusCode)).count();
        return new AlertMetrics(all.size(), (int) pending, (int) active, (int) severe, (int) urgent, (int) closed);
    }

    // ---------- 写操作 ----------

    /** 确认事件：待确认（兼容已确认）→ 待派单。 */
    public DemoAlert confirm(String id, ConfirmRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : req.operator());
        return mutate(id, idemKey, "confirm",
                List.of(AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED), alert -> {
                    alert.statusCode = AlertStatuses.PENDING_ASSIGNMENT;
                    alert.confirmUser = operator;
                    alert.confirmTime = nowHms();
                    addEvent(alert, AlertTimelineEventTypes.CONFIRMED, "active",
                            operator + " 确认事件，等待派单");
                    return alert;
                });
    }

    /** 派单：待派单 → 待处理，写入责任人 code / 姓名快照、优先级与 SLA 截止时间。 */
    public DemoAlert assign(String id, AssignRequest req, String idemKey) {
        return mutate(id, idemKey, "assign", List.of(AlertStatuses.PENDING_ASSIGNMENT), alert -> {
            DemoUser user = resolveAssignee(
                    req == null ? null : req.assigneeId(),
                    req == null ? null : firstNonBlank(req.assignee(), req.assigneeName()));
            if (user == null) {
                throw ApiException.unprocessable("派单必须指定有效责任人（userId 或唯一姓名）");
            }
            String priority = firstNonBlank(req == null ? null : req.priority(), "普通");
            int limitMin = req != null && req.limitMin() != null ? req.limitMin()
                    : req != null && req.slaLimitMin() != null ? req.slaLimitMin()
                    : alert.slaLimitMin != null ? alert.slaLimitMin : 15;
            alert.assigneeUserCode = user.id();
            alert.assignee = user.name();
            alert.priority = priority;
            alert.slaLimitMin = limitMin;
            OffsetDateTime now = OffsetDateTime.now(clock);
            alert.slaDeadline = now.plusMinutes(limitMin);
            alert.slaRemainingSec = (long) limitMin * 60;
            alert.statusCode = AlertStatuses.PENDING_PROCESS;
            alert.mobileStage = MobileStages.PENDING;
            String note = req != null && req.note() != null && !req.note().isBlank()
                    ? "，备注：" + req.note() : "";
            addEvent(alert, AlertTimelineEventTypes.ASSIGNED, "active",
                    "事件已派发给" + user.name() + "（" + priority + "优先级，时限" + limitMin + "分钟）" + note);
            return alert;
        });
    }

    /** 接单 / 开始处理：待处理 → 处理中。 */
    public DemoAlert start(String id, StartRequest req, String idemKey) {
        return mutate(id, idemKey, "start", List.of(AlertStatuses.PENDING_PROCESS), alert -> {
            alert.statusCode = AlertStatuses.PROCESSING;
            alert.acceptTime = nowHms();
            alert.mobileStage = MobileStages.PROCESSING;
            String handler = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
            addEvent(alert, AlertTimelineEventTypes.STARTED, "active", handler + " 接单，开始现场处置");
            return alert;
        });
    }

    /** 移动端接单：主状态保持待处理，仅推进 mobileStage 至 ACCEPTED。 */
    public DemoAlert mobileAccept(String id, StartRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
        return mutate(id, idemKey, "accept", List.of(AlertStatuses.PENDING_PROCESS), alert -> {
            if (alert.mobileStage != null && !MobileStages.PENDING.equals(alert.mobileStage)) {
                throw ApiException.conflict(STATE_MSG + "（当前移动端阶段：" + alert.mobileStage + "）");
            }
            alert.mobileStage = MobileStages.ACCEPTED;
            alert.acceptedAt = OffsetDateTime.now(clock);
            addEvent(alert, AlertTimelineEventTypes.ACCEPTED, "active", operator + " 已移动端接单，正在赶赴现场");
            return alert;
        });
    }

    /** 移动端确认到场：mobileStage ACCEPTED → ARRIVED，主状态保持待处理。 */
    public DemoAlert mobileArrive(String id, StartRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
        return mutate(id, idemKey, "arrive", List.of(AlertStatuses.PENDING_PROCESS), alert -> {
            if (!MobileStages.ACCEPTED.equals(alert.mobileStage)) {
                throw ApiException.conflict(STATE_MSG + "（需先移动端接单）");
            }
            alert.mobileStage = MobileStages.ARRIVED;
            alert.arrivedAt = OffsetDateTime.now(clock);
            addEvent(alert, AlertTimelineEventTypes.ARRIVED, "active", operator + " 已到达现场");
            return alert;
        });
    }

    /** 提交处置结果：处理中 → 待复核（严重/紧急事件必须再经 review 才能关闭）。 */
    public DemoAlert treatment(String id, TreatmentRequest req, String idemKey) {
        if (req == null || req.measures() == null || req.measures().isEmpty()) {
            throw ApiException.unprocessable("处置措施不能为空");
        }
        return mutate(id, idemKey, "treatment", List.of(AlertStatuses.PROCESSING), alert -> {
            String handler = firstNonBlank(alert.assignee, demoUser.name());
            TreatmentRecord record = new TreatmentRecord(
                    List.copyOf(req.measures()),
                    firstNonBlank(req.result(), "风险已解除"),
                    firstNonBlank(req.attachment(), req.evidence(), "现场处置证据（Mock）"),
                    req.note(), nowHms(), handler);
            alert.treatment = record;
            alert.statusCode = AlertStatuses.PENDING_REVIEW;
            boolean highRisk = RiskLevels.isHigh(alert.riskCode);
            addEvent(alert, AlertTimelineEventTypes.TREATMENT_SUBMITTED, "active",
                    "提交处置结果：" + String.join("、", req.measures())
                            + "，" + (highRisk ? "等待管理复核" : "等待复核"));
            return alert;
        });
    }

    /** 复核通过并关闭：待复核 → 已关闭。 */
    public DemoAlert review(String id, ReviewRequest req, String idemKey) {
        String reviewer = pickOperator(req == null ? null : firstNonBlank(req.reviewer(), req.operator()));
        return mutate(id, idemKey, "review", List.of(AlertStatuses.PENDING_REVIEW), alert -> {
            alert.statusCode = AlertStatuses.CLOSED;
            alert.reviewUser = reviewer;
            alert.reviewTime = nowHms();
            alert.reviewNote = req == null ? null : firstNonBlank(req.note(), req.reviewNote());
            alert.slaDeadline = null;
            alert.slaRemainingSec = null;
            addEvent(alert, AlertTimelineEventTypes.CLOSED, "done", "复核完成，事件关闭");
            return alert;
        });
    }

    /** 复核驳回：待复核 → 处理中。 */
    public DemoAlert reviewReject(String id, ReviewRejectRequest req, String idemKey) {
        String reason = req != null && req.reason() != null ? req.reason() : "处置不充分";
        return mutate(id, idemKey, "review-reject", List.of(AlertStatuses.PENDING_REVIEW), alert -> {
            alert.statusCode = AlertStatuses.PROCESSING;
            addEvent(alert, AlertTimelineEventTypes.REVIEW_REJECTED, "active",
                    "复核驳回：" + reason + "，继续现场处置");
            return alert;
        });
    }

    /** 转派：状态不变，更换责任人 code / 姓名快照并记录时间线。 */
    public DemoAlert transfer(String id, TransferRequest req, String idemKey) {
        return mutate(id, idemKey, "transfer",
                List.of(AlertStatuses.PENDING_ASSIGNMENT, AlertStatuses.PENDING_PROCESS,
                        AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW), alert -> {
                    DemoUser user = resolveAssignee(
                            req == null ? null : req.assigneeId(),
                            req == null ? null : req.assignee());
                    if (user == null) {
                        throw ApiException.unprocessable("转派必须指定有效责任人（userId 或唯一姓名）");
                    }
                    String oldName = alert.assignee;
                    alert.assigneeUserCode = user.id();
                    alert.assignee = user.name();
                    String note = req != null && req.note() != null && !req.note().isBlank()
                            ? "：" + req.note() : "";
                    addEvent(alert, AlertTimelineEventTypes.TRANSFERRED, "done",
                            "事件由" + oldName + "转派给" + user.name() + note);
                    return alert;
                });
    }

    /** 事件升级：记录原等级 code、更新风险等级（状态不变）。 */
    public DemoAlert escalate(String id, EscalateRequest req, String idemKey) {
        return mutate(id, idemKey, "escalate", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.PENDING_ASSIGNMENT,
                        AlertStatuses.PENDING_PROCESS, AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW,
                        AlertStatuses.ESCALATED), alert -> {
                    String oldCode = alert.riskCode;
                    String target = RiskLevels.normalize(req != null ? req.level() : null);
                    if (target == null) {
                        target = RiskLevels.URGENT;
                    }
                    if (!RiskLevels.isValid(target)) {
                        throw ApiException.unprocessable("非法风险等级: " + req.level());
                    }
                    if (RiskLevels.URGENT.equals(oldCode) || target.equals(oldCode)) {
                        throw ApiException.conflict("告警已处于最高风险等级或目标等级未升高: " + RiskLevels.label(oldCode));
                    }
                    alert.upgradedFromCode = oldCode;
                    alert.previousRiskLevelCode = oldCode;
                    alert.riskCode = target;
                    // B5-01: 升级是风险等级变化，主生命周期 statusCode 保持原值，不进入 ESCALATED 状态
                    if (AlertStatuses.ESCALATED.equals(alert.statusCode)) {
                        alert.statusCode = AlertStatuses.PROCESSING;
                    }
                    String reason = req != null && req.reason() != null ? req.reason() : "现场风险升高";
                    addEvent(alert, AlertTimelineEventTypes.ESCALATED, "active",
                            "事件由" + RiskLevels.label(oldCode) + "升级为" + RiskLevels.label(target) + "：" + reason);
                    return alert;
                });
    }

    /** 人工接管（PLC 联动失败兜底，仅记录业务，不连接真实 PLC）。 */
    public DemoAlert takeover(String id, TakeoverRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : req.operator());
        return mutate(id, idemKey, "takeover", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.PENDING_ASSIGNMENT,
                        AlertStatuses.PENDING_PROCESS, AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW,
                        AlertStatuses.ESCALATED), alert -> {
                    alert.takeover = true;
                    String reason = req != null && req.reason() != null && !req.reason().isBlank()
                            ? "，原因：" + req.reason() : "";
                    addEvent(alert, AlertTimelineEventTypes.TAKEOVER, "active",
                            operator + " 执行人工接管，现场确认设备状态" + reason);
                    return alert;
                });
    }

    /** 发起联动演示：mode=success 七步全部成功；mode=fail 在 PLC 回执处失败。 */
    public DemoAlert linkage(String id, LinkageRequest req, String idemKey) {
        boolean failMode = req != null && "fail".equalsIgnoreCase(req.mode());
        return mutate(id, idemKey, "linkage", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.PENDING_ASSIGNMENT,
                        AlertStatuses.PENDING_PROCESS, AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW,
                        AlertStatuses.ESCALATED), alert -> {
                    List<LinkageStep> tpl = AlertDemoSeeder.linkageTemplate();
                    List<LinkageStep> steps = new ArrayList<>();
                    String hms = nowHms();
                    for (int i = 0; i < tpl.size(); i++) {
                        LinkageStep s = tpl.get(i);
                        boolean isPlc = "plc".equals(s.id());
                        if (failMode && isPlc) {
                            steps.add(s.withState("failed", "回执超时", hms));
                        } else if (!failMode || i < tpl.size() - 1) {
                            steps.add(s.withState("success", successDetail(s.id()), hms));
                        } else {
                            steps.add(s);
                        }
                    }
                    alert.linkage = steps;
                    if (failMode) {
                        alert.linkageFailed = true;
                        alert.linkageFinished = false;
                        addEvent(alert, AlertTimelineEventTypes.LINKAGE_FAILED, "active",
                                "PLC 回执超时，无法确认设备已停止，请立即人工接管");
                    } else {
                        alert.linkageFailed = false;
                        alert.linkageFinished = true;
                        addEvent(alert, AlertTimelineEventTypes.LINKAGE_STARTED, "done", "安全联动执行完成");
                    }
                    return alert;
                });
    }

    // ---------- 感知风险（人员越界 / 设备碰撞 / AI 复核）→ 生成告警，统一进入 Alert 主链 ----------

    /**
     * 通用风险建单：人员越界、设备碰撞等感知模块统一入口。
     * 入参 risk 为风险机器 code（{@link RiskLevels}），provenance 三字段记录判定来源。
     */
    public synchronized DemoAlert createRiskAlert(NewRiskAlert draft) {
        if (draft.dedupKey() != null && !draft.dedupKey().isBlank()) {
            DemoAlert open = findOpenByDedupKey(draft.dedupKey());
            if (open != null) {
                log.info("命中未关闭风险去重键 {}，复用告警 {}", draft.dedupKey(), open.id);
                return open;
            }
        }
        String riskCode = RiskLevels.normalize(draft.riskCode());
        if (riskCode == null) {
            throw ApiException.unprocessable("非法风险等级: " + draft.riskCode());
        }
        String id = nextAlertId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String hms = now.format(HMS);
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = draft.title();
        a.riskCode = riskCode;
        a.eventType = draft.eventType();
        a.time = hms;
        a.area = draft.area();
        a.target = draft.target();
        a.source = draft.source();
        a.statusCode = AlertStatuses.PENDING_CONFIRM;
        a.assignee = UNASSIGNED;
        a.dedupKey = draft.dedupKey();
        applyProvenance(a, draft.ruleId(), draft.ruleVersion(),
                draft.decisionSourceType(), draft.decisionSourceCode(), draft.decisionSourceVersion());
        a.durationSec = draft.durationSec() == null ? 0 : draft.durationSec();
        a.evidence = draft.evidence();
        a.linkageAvailable = RiskLevels.isHigh(riskCode);
        a.linkage = AlertDemoSeeder.linkageTemplate();
        a.occurredAt = now;
        a.updatedAt = now;
        List<TimelineEvent> nodes = new ArrayList<>();
        nodes.add(TimelineEvent.event(now, hms, draft.firstTimeline(), "done",
                AlertTimelineEventTypes.CREATED, 1));
        nodes.add(TimelineEvent.event(now, hms, draft.secondTimeline(), "active",
                AlertTimelineEventTypes.NOTE, 2));
        a.timeline = nodes;
        repository.save(a);
        notifier.changed("new", a);
        log.info("感知风险生成告警 id={} source={} risk={} dedup={}", id, a.source, riskCode, a.dedupKey);
        return get(id);
    }

    /** 按去重键查找未关闭告警（不存在返回 null）。 */
    public DemoAlert findOpenByDedupKey(String dedupKey) {
        return repository.findOpenByDedupKey(dedupKey).orElse(null);
    }

    /**
     * 风险升级（如碰撞距离从严重继续下降到紧急）：更新同一告警等级 code 并广播 alert.escalated，
     * 不新建第二条告警；不改变处置主状态。入参 target 可为 code 或中文标签。
     */
    public synchronized DemoAlert upgradeRisk(String id, String targetCodeOrLabel, String reason) {
        DemoAlert alert = requireAlert(id);
        String target = RiskLevels.normalize(targetCodeOrLabel);
        if (!RiskLevels.isValid(target)) {
            throw ApiException.unprocessable("非法风险等级: " + targetCodeOrLabel);
        }
        String old = alert.riskCode;
        alert.upgradedFromCode = old;
        alert.riskCode = target;
        alert.linkageAvailable = true;
        addEvent(alert, AlertTimelineEventTypes.ESCALATED, "active",
                "风险由" + RiskLevels.label(old) + "升级为" + RiskLevels.label(target) + "：" + reason);
        alert.updatedAt = OffsetDateTime.now(clock);
        repository.save(alert);
        notifier.changed("escalate", alert);
        return get(id);
    }

    /** 由已确认违规的 AI 事件创建一条新的安全告警（状态：待确认，来源：AI违规）。 */
    public DemoAlert createFromAi(NewAlertFromAi draft) {
        String riskCode = RiskLevels.normalize(draft.risk());
        return createRiskAlert(new NewRiskAlert(
                "AI违规", null, draft.title(), draft.eventType(), riskCode, draft.area(),
                draft.target(), draft.ruleId(), draft.ruleVersion(),
                DecisionSources.AI_MODEL, draft.modelCode(), draft.modelVersion(),
                draft.durationSec(), draft.evidence(),
                "AI 识别到" + draft.eventType(),
                "AI 复核确认为真实违规（来源事件 " + draft.aiEventId() + "），生成安全告警"));
    }

    /** 边缘断网事件补传建单（不新建 EdgeAlert，仍是同一条 Alert 主链）。 */
    public synchronized DemoAlert createEdgeReplayAlert(EdgeReplayDraft draft) {
        String riskCode = RiskLevels.normalize(draft.risk());
        String id = nextAlertId();
        OffsetDateTime occurredAt = draft.edgeOccurredAt();
        OffsetDateTime syncedAt = OffsetDateTime.now(clock);
        String occurredHms = occurredAt.format(HMS);
        String syncedHms = syncedAt.format(HMS);
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = draft.title();
        a.riskCode = riskCode;
        a.eventType = draft.eventType();
        a.time = occurredHms;
        a.area = draft.area();
        a.target = draft.target();
        a.source = draft.source();
        a.origin = "EDGE_REPLAY";
        a.statusCode = AlertStatuses.PENDING_CONFIRM;
        a.assignee = UNASSIGNED;
        a.dedupKey = "EDGE-REPLAY:" + draft.edgeNodeId() + ":" + draft.offlineEventId();
        applyProvenance(a, draft.ruleId(), draft.ruleVersionUsed(),
                draft.decisionSourceType(), draft.decisionSourceCode(), draft.decisionSourceVersion());
        a.durationSec = draft.durationSec() == null ? 0 : draft.durationSec();
        a.evidence = draft.evidence();
        a.linkageAvailable = RiskLevels.isHigh(riskCode);
        a.linkage = AlertDemoSeeder.linkageTemplate();
        a.occurredAt = occurredAt;
        a.updatedAt = syncedAt;
        long delaySec = Duration.between(occurredAt, syncedAt).getSeconds();
        DemoAlert.EdgeReplayMeta meta = new DemoAlert.EdgeReplayMeta();
        meta.edgeNodeId = draft.edgeNodeId();
        meta.offlineEventId = draft.offlineEventId();
        meta.offlineOccurred = true;
        meta.syncDelaySec = Math.max(0, delaySec);
        meta.ruleVersionUsed = draft.ruleVersionUsed();
        meta.syncedAt = syncedAt;
        a.edgeReplay = meta;
        int seq = 1;
        List<TimelineEvent> nodes = new ArrayList<>();
        seq = add(nodes, occurredAt, occurredHms, "边缘本地产生风险判定：" + draft.localJudgement(), "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        seq = add(nodes, occurredAt, occurredHms, "边缘本地联动执行完成（" + draft.localLinkageText() + "）", "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        seq = add(nodes, occurredAt, occurredHms, "云连接中断，事件未上报中心", "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        seq = add(nodes, occurredAt, occurredHms, "事件进入边缘离线缓存（事件号 " + draft.offlineEventId() + "）", "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        seq = add(nodes, syncedAt, syncedHms, "云边链路恢复连接", "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        seq = add(nodes, syncedAt, syncedHms, "离线事件幂等补传成功", "done",
                AlertTimelineEventTypes.EDGE_REPLAY, seq);
        add(nodes, syncedAt, syncedHms,
                "平台接收告警（边缘发生 " + occurredHms + "，补传延迟 " + Math.max(0, delaySec) + " 秒）", "active",
                AlertTimelineEventTypes.CREATED, seq);
        a.timeline = nodes;
        repository.save(a);
        notifier.changed("new", a);
        log.info("边缘补传生成告警 id={} edge={} offlineEvent={} delaySec={}",
                id, draft.edgeNodeId(), draft.offlineEventId(), Math.max(0, delaySec));
        return get(id);
    }

    /** 边缘补传建单入参（alert 模块不反向依赖 ops 模块）。 */
    public record EdgeReplayDraft(String edgeNodeId, String offlineEventId, OffsetDateTime edgeOccurredAt,
                                  String source, String title, String eventType, String risk, String area,
                                  String target, String ruleId, String ruleVersionUsed,
                                  String decisionSourceType, String decisionSourceCode, String decisionSourceVersion,
                                  Integer durationSec, AlertEvidence evidence,
                                  String localJudgement, String localLinkageText) {
    }

    /** 感知模块（人员/设备/AI）风险建单入参；riskCode 为机器 code。 */
    public record NewRiskAlert(String source, String dedupKey, String title, String eventType, String riskCode,
                               String area, String target, String ruleId, String ruleVersion,
                               String decisionSourceType, String decisionSourceCode, String decisionSourceVersion,
                               Integer durationSec, AlertEvidence evidence,
                               String firstTimeline, String secondTimeline) {
    }

    /** AI 生成告警的入参（record，避免 alert 模块反向依赖 ai 模块）。 */
    public record NewAlertFromAi(String aiEventId, String title, String eventType, String risk,
                                 String area, String target, int durationSec, String ruleId,
                                 String ruleVersion, String modelCode, String modelVersion,
                                 AlertEvidence evidence) {
    }

    /** 首页「模拟风险」演示事件固定 ID（SIMULATED）。 */
    public static final String DEMO_RISK_ALERT_ID = "ALM-DEMO-RISK";

    /** 首页风险演示开关（SIMULATED）：走真实存储与 WS 广播。 */
    public DemoAlert demoRisk(boolean active) {
        DemoAlert existing = repository.findById(DEMO_RISK_ALERT_ID).orElse(null);
        if (active) {
            if (existing != null && !AlertStatuses.CLOSED.equals(existing.statusCode)) {
                return existing;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            String hms = now.format(HMS);
            DemoAlert a = existing == null ? new DemoAlert() : existing;
            a.id = DEMO_RISK_ALERT_ID;
            a.title = "人员进入龙门吊作业区域";
            a.riskCode = RiskLevels.URGENT;
            a.eventType = "危险区域闯入";
            a.time = hms;
            a.area = "龙门吊作业区";
            a.target = "赵磊（P-1003）";
            a.source = "人员安全";
            a.statusCode = AlertStatuses.PENDING_CONFIRM;
            a.assignee = UNASSIGNED;
            // 演示开关语义为“规则引擎判定”，provenance 标记为 RULE。
            applyProvenance(a, "RULE-PER-001", "v3.3", DecisionSources.RULE, "RULE-PER-001", "v3.3");
            a.durationSec = 0;
            a.evidence = PersonnelEvidence.of(java.util.List.of(),
                    "龙门吊作业区 · 动态禁入区内", "吊装作业禁入区（红色边界）",
                    "BAND-1003", "在线 · 持续震动提醒中", "116 次/分");
            a.linkageAvailable = true;
            a.linkage = AlertDemoSeeder.linkageTemplate();
            a.occurredAt = now;
            a.updatedAt = now;
            int seq = 1;
            List<TimelineEvent> nodes = new ArrayList<>();
            seq = add(nodes, now, hms, "赵磊进入龙门吊电子围栏范围", "done", AlertTimelineEventTypes.CREATED, seq);
            seq = add(nodes, now, hms, "规则引擎判定为紧急事件", "done", AlertTimelineEventTypes.NOTE, seq);
            add(nodes, now, hms, "通知已生成，等待安全员人工确认", "active", AlertTimelineEventTypes.NOTE, seq);
            a.timeline = nodes;
            repository.save(a);
            notifier.changed("new", a);
            return get(a.id);
        }
        if (existing != null && !AlertStatuses.CLOSED.equals(existing.statusCode)) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            addEvent(existing, AlertTimelineEventTypes.CLOSED, "done", "风险演示解除，事件关闭");
            existing.statusCode = AlertStatuses.CLOSED;
            existing.updatedAt = now;
            repository.save(existing);
            notifier.changed("review", existing);
            return get(existing.id);
        }
        return existing;
    }

    /** 生成下一个告警编号（Phase B：委托 {@link AlertNumberGenerator}，格式 ALM-yyyyMMdd-NNN 不变）。 */
    private String nextAlertId() {
        return numberGenerator.nextAlertNumber();
    }

    // ---------- 内部辅助 ----------

    /** 解析责任人：优先 USR code，其次唯一姓名；无效返回 null（调用方抛 422）。 */
    private DemoUser resolveAssignee(String userCode, String name) {
        String code = firstNonBlank(userCode, null);
        // “待分配”不是有效责任人
        if (name != null && UNASSIGNED.equals(name)) {
            name = null;
        }
        DemoUser user = masterData.resolveUser(code, name);
        return user;
    }

    /** 统一写入判定来源 provenance；ruleId/ruleVersion 仅在确为规则触发时保留。 */
    private void applyProvenance(DemoAlert a, String ruleId, String ruleVersion,
                                 String sourceType, String sourceCode, String sourceVersion) {
        a.decisionSourceType = sourceType;
        a.decisionSourceCode = sourceCode;
        a.decisionSourceVersion = sourceVersion;
        a.ruleId = DecisionSources.RULE.equals(sourceType) ? firstNonBlank(ruleId, sourceCode) : ruleId;
        a.ruleVersion = DecisionSources.RULE.equals(sourceType)
                ? firstNonBlank(ruleVersion, sourceVersion) : ruleVersion;
    }

    private DemoAlert mutate(String id, String idemKey, String op, List<String> allowed,
                             UnaryOperator<DemoAlert> changer) {
        synchronized (id.intern()) {
            idempotency.process(idemKey, () -> {
                DemoAlert alert = requireAlert(id);
                requireStatus(alert, allowed);
                DemoAlert changed = changer.apply(alert);
                changed.updatedAt = OffsetDateTime.now(clock);
                stampSequences(changed);
                refreshSla(changed);
                repository.save(changed);
                notifier.changed(op, changed);
                log.info("告警状态变更 id={} op={} status={}", id, op, changed.statusCode);
                return op + ":" + id;
            });
            return get(id);
        }
    }

    private DemoAlert requireAlert(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("告警不存在: " + id));
    }

    private void requireStatus(DemoAlert alert, List<String> allowed) {
        if (!allowed.contains(alert.statusCode)) {
            throw ApiException.conflict(STATE_MSG + "（当前状态：" + AlertStatuses.label(alert.statusCode) + "）");
        }
    }

    /** 追加机器事件时间线：既有 active 节点先收敛为 done，再按序追加并维护 sequenceNo。 */
    private void addEvent(DemoAlert alert, String eventType, String state, String text) {
        List<TimelineEvent> nodes = new ArrayList<>(alert.timeline);
        nodes.replaceAll(n -> "active".equals(n.state())
                ? TimelineEvent.event(n.at(), n.time(), n.text(), "done", n.eventType(), n.sequenceNo()) : n);
        OffsetDateTime now = OffsetDateTime.now(clock);
        int nextSeq = nodes.stream().mapToInt(n -> n.sequenceNo() == null ? 0 : n.sequenceNo()).max().orElse(0) + 1;
        nodes.add(TimelineEvent.event(now, nowHms(), text, state, eventType, nextSeq));
        alert.timeline = List.copyOf(nodes);
    }

    /** 保证时间线序号连续（Seed / 建单阶段未显式编号的节点按顺序补齐）。 */
    private void stampSequences(DemoAlert alert) {
        List<TimelineEvent> nodes = new ArrayList<>();
        int seq = 1;
        for (TimelineEvent n : alert.timeline) {
            String type = n.eventType() == null ? AlertTimelineEventTypes.NOTE : n.eventType();
            nodes.add(TimelineEvent.event(n.at(), n.time(), n.text(), n.state(), type, seq++));
        }
        alert.timeline = List.copyOf(nodes);
    }

    private static int add(List<TimelineEvent> nodes, OffsetDateTime at, String hms, String text, String state,
                           String eventType, int seq) {
        nodes.add(TimelineEvent.event(at, hms, text, state, eventType, seq));
        return seq + 1;
    }

    /** 依据 slaDeadline 重算剩余秒数；已关闭清空 SLA。 */
    private void refreshSla(DemoAlert alert) {
        if (AlertStatuses.CLOSED.equals(alert.statusCode) || alert.slaDeadline == null) {
            if (AlertStatuses.CLOSED.equals(alert.statusCode)) {
                alert.slaRemainingSec = null;
            }
            return;
        }
        alert.slaRemainingSec = Duration.between(OffsetDateTime.now(clock), alert.slaDeadline).getSeconds();
    }

    private String pickOperator(String explicit) {
        return firstNonBlank(explicit, demoUser.name());
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) {
                return v;
            }
        }
        return null;
    }

    private static String successDetail(String stepId) {
        return switch (stepId) {
            case "sound-light", "shutdown" -> "已执行";
            case "band", "slowdown" -> "已发送";
            case "safety-officer", "dispatcher" -> "已送达";
            case "plc" -> "已确认";
            default -> "已完成";
        };
    }

    private String nowHms() {
        return OffsetDateTime.now(clock).format(HMS);
    }
}
