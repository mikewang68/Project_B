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
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.PersonnelEvidence;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.MobileStages;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.model.TreatmentRecord;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.repository.AlertQuery;
import com.bproject.safety.module.alert.seed.AlertDemoSeeder;
import com.bproject.safety.support.demo.DemoUserProperties;
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
 * <p>主链路：待确认 →(confirm) 待派单 →(assign) 待处理 →(start) 处理中
 * →(treatment) 待复核 →(review) 已关闭；非法流转统一抛 409 STATE_CONFLICT。
 * 本阶段不做最终状态机设计，仅对齐前端现有演示逻辑。</p>
 */
@Service
public class AlertService {

    private static final Logger log = LoggerFactory.getLogger(AlertService.class);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");
    private static final String STATE_MSG = "当前状态不允许执行该操作";
    private static final List<String> RISK_LEVELS = List.of("一般", "预警", "严重", "紧急");

    private final AlertRepository repository;
    private final IdempotencyService idempotency;
    private final DemoUserProperties demoUser;
    private final Clock clock;
    private final AlertChangeNotifier notifier;

    public AlertService(AlertRepository repository, IdempotencyService idempotency,
                        DemoUserProperties demoUser, Clock clock, AlertChangeNotifier notifier) {
        this.repository = repository;
        this.idempotency = idempotency;
        this.demoUser = demoUser;
        this.clock = clock;
        this.notifier = notifier == null ? AlertChangeNotifier.NOOP : notifier;
    }

    // ---------- 查询 ----------

    public AlertRepository.AlertPageResult page(AlertQuery query) {
        AlertRepository.AlertPageResult result = repository.page(query);
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
        long pending = all.stream().filter(a -> AlertStatuses.PENDING_CONFIRM.equals(a.status)).count();
        long active = all.stream().filter(a -> AlertStatuses.ACTIVE.contains(a.status)).count();
        long severe = all.stream()
                .filter(a -> "严重".equals(a.risk) && !AlertStatuses.CLOSED.equals(a.status)).count();
        long urgent = all.stream()
                .filter(a -> "紧急".equals(a.risk) && !AlertStatuses.CLOSED.equals(a.status)).count();
        long closed = all.stream().filter(a -> AlertStatuses.CLOSED.equals(a.status)).count();
        return new AlertMetrics(all.size(), pending, active, severe, urgent, closed);
    }

    // ---------- 写操作 ----------

    /** 确认事件：待确认（兼容已确认）→ 待派单。 */
    public DemoAlert confirm(String id, ConfirmRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : req.operator());
        return mutate(id, idemKey, "confirm",
                List.of(AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED), alert -> {
                    alert.status = AlertStatuses.TO_ASSIGN;
                    alert.confirmUser = operator;
                    alert.confirmTime = nowHms();
                    pushTimeline(alert, operator + " 确认事件，等待派单", "active");
                    return alert;
                });
    }

    /** 派单：待派单 → 待处理，写入责任人、优先级与 SLA 截止时间。 */
    public DemoAlert assign(String id, AssignRequest req, String idemKey) {
        return mutate(id, idemKey, "assign", List.of(AlertStatuses.TO_ASSIGN), alert -> {
            String assignee = firstNonBlank(req == null ? null : req.assignee(),
                    req == null ? null : req.assigneeName(), alert.assignee);
            if (assignee == null || assignee.isBlank() || "待分配".equals(assignee)) {
                throw ApiException.unprocessable("派单必须指定责任人");
            }
            String priority = firstNonBlank(req == null ? null : req.priority(), "普通");
            int limitMin = req != null && req.limitMin() != null ? req.limitMin()
                    : req != null && req.slaLimitMin() != null ? req.slaLimitMin()
                    : alert.slaLimitMin != null ? alert.slaLimitMin : 15;
            alert.assignee = assignee;
            alert.priority = priority;
            alert.slaLimitMin = limitMin;
            alert.slaDeadline = OffsetDateTime.now(clock).plusMinutes(limitMin);
            alert.slaRemainingSec = (long) limitMin * 60;
            alert.status = AlertStatuses.TO_HANDLE;
            // 派单后进入移动端“待接单”阶段（主状态仍为待处理，不破坏管理端状态机）
            alert.mobileStage = MobileStages.PENDING;
            String note = req != null && req.note() != null && !req.note().isBlank()
                    ? "，备注：" + req.note() : "";
            pushTimeline(alert, "事件已派发给" + assignee + "（" + priority + "优先级，时限"
                    + limitMin + "分钟）" + note, "active");
            return alert;
        });
    }

    /** 接单 / 开始处理：待处理 → 处理中。 */
    public DemoAlert start(String id, StartRequest req, String idemKey) {
        return mutate(id, idemKey, "start", List.of(AlertStatuses.TO_HANDLE), alert -> {
            alert.status = AlertStatuses.HANDLING;
            alert.acceptTime = nowHms();
            alert.mobileStage = MobileStages.PROCESSING;
            String handler = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
            pushTimeline(alert, handler + " 接单，开始现场处置", "active");
            return alert;
        });
    }

    /**
     * 移动端接单（Backend Demo 过渡设计）：主状态保持“待处理”，仅推进 mobileStage 至 ACCEPTED。
     * 与管理端 start 区分：移动端需要“已接单→已到场→开始处理”的现场节奏。
     */
    public DemoAlert mobileAccept(String id, StartRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
        return mutate(id, idemKey, "accept", List.of(AlertStatuses.TO_HANDLE), alert -> {
            if (alert.mobileStage != null
                    && !MobileStages.PENDING.equals(alert.mobileStage)) {
                throw ApiException.conflict(STATE_MSG + "（当前移动端阶段：" + alert.mobileStage + "）");
            }
            alert.mobileStage = MobileStages.ACCEPTED;
            alert.acceptedAt = OffsetDateTime.now(clock);
            pushTimeline(alert, operator + " 已移动端接单，正在赶赴现场", "active");
            return alert;
        });
    }

    /** 移动端确认到场：mobileStage ACCEPTED → ARRIVED，主状态保持“待处理”。 */
    public DemoAlert mobileArrive(String id, StartRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : firstNonBlank(req.handler(), req.operator()));
        return mutate(id, idemKey, "arrive", List.of(AlertStatuses.TO_HANDLE), alert -> {
            if (!MobileStages.ACCEPTED.equals(alert.mobileStage)) {
                throw ApiException.conflict(STATE_MSG + "（需先移动端接单）");
            }
            alert.mobileStage = MobileStages.ARRIVED;
            alert.arrivedAt = OffsetDateTime.now(clock);
            pushTimeline(alert, operator + " 已到达现场", "active");
            return alert;
        });
    }

    /** 提交处置结果：处理中 → 待复核（严重/紧急事件必须再经 review 才能关闭）。 */
    public DemoAlert treatment(String id, TreatmentRequest req, String idemKey) {
        if (req == null || req.measures() == null || req.measures().isEmpty()) {
            throw ApiException.unprocessable("处置措施不能为空");
        }
        return mutate(id, idemKey, "treatment", List.of(AlertStatuses.HANDLING), alert -> {
            String handler = firstNonBlank(alert.assignee, demoUser.name());
            TreatmentRecord record = new TreatmentRecord(
                    List.copyOf(req.measures()),
                    firstNonBlank(req.result(), "风险已解除"),
                    firstNonBlank(req.attachment(), req.evidence(), "现场处置证据（Mock）"),
                    req.note(), nowHms(), handler);
            alert.treatment = record;
            alert.status = AlertStatuses.TO_REVIEW;
            boolean highRisk = "严重".equals(alert.risk) || "紧急".equals(alert.risk);
            pushTimeline(alert, "提交处置结果：" + String.join("、", req.measures())
                    + "，" + (highRisk ? "等待管理复核" : "等待复核"), "active");
            return alert;
        });
    }

    /** 复核通过并关闭：待复核 → 已关闭。 */
    public DemoAlert review(String id, ReviewRequest req, String idemKey) {
        String reviewer = pickOperator(req == null ? null : firstNonBlank(req.reviewer(), req.operator()));
        return mutate(id, idemKey, "review", List.of(AlertStatuses.TO_REVIEW), alert -> {
            alert.status = AlertStatuses.CLOSED;
            alert.reviewUser = reviewer;
            alert.reviewTime = nowHms();
            alert.reviewNote = req == null ? null : firstNonBlank(req.note(), req.reviewNote());
            alert.slaDeadline = null;
            alert.slaRemainingSec = null;
            pushTimeline(alert, "复核完成，事件关闭", "done");
            return alert;
        });
    }

    /** 复核驳回：待复核 → 处理中。 */
    public DemoAlert reviewReject(String id, ReviewRejectRequest req, String idemKey) {
        String reason = req != null && req.reason() != null ? req.reason() : "处置不充分";
        return mutate(id, idemKey, "review-reject", List.of(AlertStatuses.TO_REVIEW), alert -> {
            alert.status = AlertStatuses.HANDLING;
            pushTimeline(alert, "复核驳回：" + reason + "，继续现场处置", "active");
            return alert;
        });
    }

    /** 转派：状态不变，更换责任人并记录时间线。 */
    public DemoAlert transfer(String id, TransferRequest req, String idemKey) {
        return mutate(id, idemKey, "transfer",
                List.of(AlertStatuses.TO_ASSIGN, AlertStatuses.TO_HANDLE,
                        AlertStatuses.HANDLING, AlertStatuses.TO_REVIEW), alert -> {
                    String assignee = firstNonBlank(req == null ? null : req.assignee(), null);
                    if (assignee == null) {
                        throw ApiException.unprocessable("转派必须指定责任人");
                    }
                    alert.assignee = assignee;
                    String note = req != null && req.note() != null && !req.note().isBlank()
                            ? "：" + req.note() : "";
                    pushTimeline(alert, "事件转派给" + assignee + note, "done");
                    return alert;
                });
    }

    /** 事件升级：记录原等级、更新风险等级（状态不变）。 */
    public DemoAlert escalate(String id, EscalateRequest req, String idemKey) {
        return mutate(id, idemKey, "escalate", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.TO_ASSIGN,
                        AlertStatuses.TO_HANDLE, AlertStatuses.HANDLING, AlertStatuses.TO_REVIEW,
                        AlertStatuses.ESCALATED), alert -> {
                    String old = alert.risk;
                    String target = req != null && req.level() != null && !req.level().isBlank()
                            ? req.level() : "紧急";
                    if (!RISK_LEVELS.contains(target)) {
                        throw ApiException.unprocessable("非法风险等级: " + target);
                    }
                    alert.upgradedFrom = old;
                    alert.risk = target;
                    if (!AlertStatuses.ESCALATED.equals(alert.status)) {
                        alert.status = AlertStatuses.ESCALATED;
                    }
                    String reason = req != null && req.reason() != null ? req.reason() : "现场风险升高";
                    pushTimeline(alert, "事件由" + old + "升级为" + target + "：" + reason, "active");
                    return alert;
                });
    }

    /** 人工接管（PLC 联动失败兜底，仅记录业务，不连接真实 PLC）。 */
    public DemoAlert takeover(String id, TakeoverRequest req, String idemKey) {
        String operator = pickOperator(req == null ? null : req.operator());
        return mutate(id, idemKey, "takeover", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.TO_ASSIGN,
                        AlertStatuses.TO_HANDLE, AlertStatuses.HANDLING, AlertStatuses.TO_REVIEW,
                        AlertStatuses.ESCALATED), alert -> {
                    alert.takeover = true;
                    String reason = req != null && req.reason() != null && !req.reason().isBlank()
                            ? "，原因：" + req.reason() : "";
                    pushTimeline(alert, operator + " 执行人工接管，现场确认设备状态" + reason, "active");
                    return alert;
                });
    }

    /** 发起联动演示：mode=success 七步全部成功；mode=fail 在 PLC 回执处失败。 */
    public DemoAlert linkage(String id, LinkageRequest req, String idemKey) {
        boolean failMode = req != null && "fail".equalsIgnoreCase(req.mode());
        return mutate(id, idemKey, "linkage", List.of(
                        AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED, AlertStatuses.TO_ASSIGN,
                        AlertStatuses.TO_HANDLE, AlertStatuses.HANDLING, AlertStatuses.TO_REVIEW,
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
                        pushTimeline(alert, "PLC 回执超时，无法确认设备已停止，请立即人工接管", "active");
                    } else {
                        alert.linkageFailed = false;
                        alert.linkageFinished = true;
                        pushTimeline(alert, "安全联动执行完成", "done");
                    }
                    return alert;
                });
    }

    // ---------- 感知风险（人员越界 / 设备碰撞 / AI 复核）→ 生成告警，统一进入 Alert 主链 ----------

    /**
     * 通用风险建单：人员越界、设备碰撞等感知模块统一入口。
     *
     * <p>去重：dedupKey 非空且已存在同一去重键的未关闭告警时，直接返回原告警，不重复建单；
     * 风险继续升高时调用方应走 {@link #upgradeRisk} 升级同一告警。创建后立即广播 alert.new。</p>
     */
    public synchronized DemoAlert createRiskAlert(NewRiskAlert draft) {
        if (draft.dedupKey() != null && !draft.dedupKey().isBlank()) {
            DemoAlert open = findOpenByDedupKey(draft.dedupKey());
            if (open != null) {
                log.info("命中未关闭风险去重键 {}，复用告警 {}", draft.dedupKey(), open.id);
                return open;
            }
        }
        String id = nextAlertId();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String hms = now.format(HMS);
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = draft.title();
        a.risk = draft.risk();
        a.eventType = draft.eventType();
        a.time = hms;
        a.area = draft.area();
        a.target = draft.target();
        a.source = draft.source();
        a.status = AlertStatuses.PENDING_CONFIRM;
        a.assignee = "待分配";
        a.dedupKey = draft.dedupKey();
        a.ruleId = draft.ruleId();
        a.ruleVersion = draft.ruleVersion();
        a.durationSec = draft.durationSec() == null ? 0 : draft.durationSec();
        a.evidence = draft.evidence();
        a.linkageAvailable = "严重".equals(a.risk) || "紧急".equals(a.risk);
        a.linkage = AlertDemoSeeder.linkageTemplate();
        a.occurredAt = now;
        a.updatedAt = now;
        List<TimelineEvent> nodes = new ArrayList<>();
        nodes.add(new TimelineEvent(hms, now, draft.firstTimeline(), "done"));
        nodes.add(new TimelineEvent(hms, now, draft.secondTimeline(), "active"));
        a.timeline = nodes;
        repository.save(a);
        notifier.changed("new", a);
        log.info("感知风险生成告警 id={} source={} risk={} dedup={}", id, a.source, a.risk, a.dedupKey);
        return get(id);
    }

    /** 按去重键查找未关闭告警（不存在返回 null）。 */
    public DemoAlert findOpenByDedupKey(String dedupKey) {
        return repository.findAll().stream()
                .filter(a -> dedupKey.equals(a.dedupKey) && !AlertStatuses.CLOSED.equals(a.status))
                .findFirst().orElse(null);
    }

    /**
     * 风险升级（如碰撞距离从严重继续下降到紧急）：更新同一告警等级并广播 alert.escalated，
     * 不新建第二条告警；不改变处置主状态。
     */
    public synchronized DemoAlert upgradeRisk(String id, String target, String reason) {
        DemoAlert alert = requireAlert(id);
        if (!RISK_LEVELS.contains(target)) {
            throw ApiException.unprocessable("非法风险等级: " + target);
        }
        String old = alert.risk;
        alert.upgradedFrom = old;
        alert.risk = target;
        alert.linkageAvailable = true;
        pushTimeline(alert, "风险由" + old + "升级为" + target + "：" + reason, "active");
        alert.updatedAt = OffsetDateTime.now(clock);
        repository.save(alert);
        notifier.changed("escalate", alert);
        return get(id);
    }

    /**
     * 由已确认违规的 AI 事件创建一条新的安全告警（状态：待确认，来源：AI违规）。
     *
     * <p>alert 模块不依赖 ai 模块，因此以基础字段 + 本模块证据模型传参；创建后立即广播 alert.new，
     * 管理端 / 大屏 / 移动端会实时收到。调用方（AiEventService）负责把返回的 alertId 写回 AIEvent。</p>
     */
    public DemoAlert createFromAi(NewAlertFromAi draft) {
        return createRiskAlert(new NewRiskAlert(
                "AI违规", null, draft.title(), draft.eventType(), draft.risk(), draft.area(),
                draft.target(), draft.ruleId(), draft.ruleVersion(), draft.durationSec(), draft.evidence(),
                "AI 识别到" + draft.eventType(),
                "AI 复核确认为真实违规（来源事件 " + draft.aiEventId() + "），生成安全告警"));
    }

    /** 感知模块（人员/设备/AI）风险建单入参。 */
    public record NewRiskAlert(String source, String dedupKey, String title, String eventType, String risk,
                               String area, String target, String ruleId, String ruleVersion,
                               Integer durationSec, AlertEvidence evidence,
                               String firstTimeline, String secondTimeline) {
    }

    /** 首页「模拟风险」演示事件固定 ID（SIMULATED：不接真实感知，仅用于地图风险覆盖联动演示）。 */
    public static final String DEMO_RISK_ALERT_ID = "ALM-DEMO-RISK";

    /**
     * 首页风险演示开关（SIMULATED）：active=true 创建一条赵磊闯入龙门吊区的紧急未关闭告警；
     * active=false 将其关闭。事件走真实存储与 WS 广播，因此首页地图 / Feed / 大屏 / 移动端同步变化。
     */
    public DemoAlert demoRisk(boolean active) {
        DemoAlert existing = repository.findById(DEMO_RISK_ALERT_ID).orElse(null);
        if (active) {
            if (existing != null && !AlertStatuses.CLOSED.equals(existing.status)) {
                return existing;
            }
            OffsetDateTime now = OffsetDateTime.now(clock);
            String hms = now.format(HMS);
            DemoAlert a = existing == null ? new DemoAlert() : existing;
            a.id = DEMO_RISK_ALERT_ID;
            a.title = "人员进入龙门吊作业区域";
            a.risk = "紧急";
            a.eventType = "危险区域闯入";
            a.time = hms;
            a.area = "龙门吊作业区";
            a.target = "赵磊（P-1003）";
            a.source = "人员安全";
            a.status = AlertStatuses.PENDING_CONFIRM;
            a.assignee = "待分配";
            a.ruleId = "RULE-PER-001";
            a.ruleVersion = "v3.3";
            a.durationSec = 0;
            a.evidence = PersonnelEvidence.of(java.util.List.of(),
                    "龙门吊作业区 · 动态禁入区内", "吊装作业禁入区（红色边界）",
                    "BAND-1003", "在线 · 持续震动提醒中", "116 次/分");
            a.linkageAvailable = true;
            a.linkage = AlertDemoSeeder.linkageTemplate();
            a.occurredAt = now;
            a.updatedAt = now;
            a.timeline = new ArrayList<>(List.of(
                    new TimelineEvent(hms, now, "赵磊进入龙门吊电子围栏范围", "done"),
                    new TimelineEvent(hms, now, "规则引擎判定为紧急事件", "done"),
                    new TimelineEvent(hms, now, "通知已生成，等待安全员人工确认", "active")));
            repository.save(a);
            notifier.changed("new", a);
            return get(a.id);
        }
        if (existing != null && !AlertStatuses.CLOSED.equals(existing.status)) {
            OffsetDateTime now = OffsetDateTime.now(clock);
            pushTimeline(existing, "风险演示解除，事件关闭", "done");
            existing.status = AlertStatuses.CLOSED;
            existing.updatedAt = now;
            repository.save(existing);
            notifier.changed("review", existing);
            return get(existing.id);
        }
        return existing;
    }

    /** AI 生成告警的入参（record，避免 alert 模块反向依赖 ai 模块）。 */
    public record NewAlertFromAi(String aiEventId, String title, String eventType, String risk,
                                 String area, String target, int durationSec, String ruleId,
                                 String ruleVersion, AlertEvidence evidence) {
    }

    /** 生成下一个告警编号 ALM-yyyyMMdd-NNN（单实例 Demo 口径）。 */
    private String nextAlertId() {
        String day = OffsetDateTime.now(clock).format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        String prefix = "ALM-" + day + "-";
        long sameDay = repository.findAll().stream().filter(x -> x.id != null && x.id.startsWith(prefix)).count();
        return String.format("%s%03d", prefix, sameDay + 1);
    }

    // ---------- 内部辅助 ----------

    /**
     * 单条告警状态变更模板：加载 → 状态校验 → 业务变更 → 保存，整体受 Idempotency-Key 保护；
     * 相同 Key 的已完成重复请求直接回放当前实体，不重复追加时间线。
     */
    private DemoAlert mutate(String id, String idemKey, String op, List<String> allowed,
                             UnaryOperator<DemoAlert> changer) {
        synchronized (id.intern()) {
            // 相同 Idempotency-Key 的已完成重复请求在 process 内直接回放摘要、不再执行 changer，
            // 因此时间线与状态不会被重复写入；最终统一返回写入后的最新实体。
            idempotency.process(idemKey, () -> {
                DemoAlert alert = requireAlert(id);
                requireStatus(alert, allowed);
                DemoAlert changed = changer.apply(alert);
                changed.updatedAt = OffsetDateTime.now(clock);
                refreshSla(changed);
                repository.save(changed);
                // 仅在真正执行业务变更（非幂等回放）后广播；广播失败不影响业务结果
                notifier.changed(op, changed);
                log.info("告警状态变更 id={} op={} status={}", id, op, changed.status);
                return op + ":" + id;
            });
            return get(id);
        }
    }

    private DemoAlert requireAlert(String id) {
        Optional<DemoAlert> found = repository.findById(id);
        return found.orElseThrow(() -> ApiException.notFound("告警不存在: " + id));
    }

    private void requireStatus(DemoAlert alert, List<String> allowed) {
        if (!allowed.contains(alert.status)) {
            throw ApiException.conflict(STATE_MSG + "（当前状态：" + alert.status + "）");
        }
    }

    /** 追加时间线：既有 active 节点先收敛为 done（与前端 pushTimeline 行为一致）。 */
    private void pushTimeline(DemoAlert alert, String text, String state) {
        List<TimelineEvent> nodes = new ArrayList<>(alert.timeline);
        nodes.replaceAll(n -> "active".equals(n.state())
                ? new TimelineEvent(n.time(), n.at(), n.text(), "done") : n);
        OffsetDateTime now = OffsetDateTime.now(clock);
        nodes.add(new TimelineEvent(nowHms(), now, text, state));
        alert.timeline = List.copyOf(nodes);
    }

    /** 依据 slaDeadline 重算剩余秒数；已关闭清空 SLA。 */
    private void refreshSla(DemoAlert alert) {
        if (AlertStatuses.CLOSED.equals(alert.status) || alert.slaDeadline == null) {
            if (AlertStatuses.CLOSED.equals(alert.status)) {
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
