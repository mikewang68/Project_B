package com.bproject.safety.module.ai.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.idempotency.IdempotencyService;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.module.ai.dto.AiDtos.AiEventPage;
import com.bproject.safety.module.ai.dto.AiDtos.AiFacets;
import com.bproject.safety.module.ai.dto.AiDtos.AiMetrics;
import com.bproject.safety.module.ai.dto.AiRequests.AiAssignRequest;
import com.bproject.safety.module.ai.dto.AiRequests.FalsePositiveRequest;
import com.bproject.safety.module.ai.dto.AiRequests.ProcessRequest;
import com.bproject.safety.module.ai.dto.AiRequests.ReviewRequest;
import com.bproject.safety.module.ai.dto.AiRequests.SimulateRequest;
import com.bproject.safety.module.ai.dto.AiRequests.UncertainRequest;
import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.AiRiskLevels;
import com.bproject.safety.module.ai.model.AiTimelineEventTypes;
import com.bproject.safety.module.ai.model.AiTimelineNode;
import com.bproject.safety.module.ai.model.CameraInfo;
import com.bproject.safety.module.ai.model.AiPageResult;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.realtime.AiChangeNotifier;
import com.bproject.safety.module.ai.repository.AiEventQuery;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.ai.seed.AiDemoSeeder;
import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.AiEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.DetectionBox;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.support.demo.DemoUserProperties;
import com.bproject.safety.support.masterdata.DemoDeviceMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData;
import com.bproject.safety.support.masterdata.DemoMasterData.DemoUser;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * AI 事件服务：识别事件 / 证据 / 置信度 / 人工复核 / 派单入口。
 *
 * <p>核心边界：AIEvent 与 Alert 不是同一对象。只有「确认违规」才通过 {@link AlertService#createFromAi}
 * 创建关联 Alert 并进入统一处置主链；误报永不建 Alert；重复 confirm 不重复建。</p>
 */
@Service
public class AiEventService {

    private static final Logger log = LoggerFactory.getLogger(AiEventService.class);
    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final AiEventRepository repository;
    private final AlertService alertService;
    private final IdempotencyService idempotency;
    private final AiChangeNotifier notifier;
    private final DemoUserProperties demoUser;
    private final DemoMasterData masterData;
    private final DemoDeviceMasterData deviceMasterData;
    private final Clock clock;
    private final AiEventNumberGenerator numberGenerator;
    private final LiveEventGate gate;
    private final List<CameraInfo> cameras;

    public AiEventService(AiEventRepository repository, AlertService alertService,
                          IdempotencyService idempotency, AiChangeNotifier notifier,
                          DemoUserProperties demoUser, DemoMasterData masterData,
                          DemoDeviceMasterData deviceMasterData, Clock clock,
                          AiEventNumberGenerator numberGenerator, LiveEventGate gate) {
        this.repository = repository;
        this.alertService = alertService;
        this.idempotency = idempotency;
        this.notifier = notifier == null ? AiChangeNotifier.NOOP : notifier;
        this.demoUser = demoUser;
        this.masterData = masterData;
        this.deviceMasterData = deviceMasterData;
        this.clock = clock;
        this.numberGenerator = numberGenerator;
        this.gate = gate;
        this.cameras = new ArrayList<>(AiDemoSeeder.buildCameras(deviceMasterData));
    }

    // ---------- 查询 ----------

    public AiEventPage page(AiEventQuery query) {
        AiPageResult result = repository.page(query);
        return new AiEventPage(result.page(), result.pageSize(), result.total(),
                metrics(), facets(), result.list());
    }

    public AiMetrics metrics() {
        List<DemoAiEvent> all = repository.findAll();
        long simulated = repository.count() - AiDemoSeeder.SEED_COUNT;
        int pending = (int) all.stream()
                .filter(e -> AiReviewStatuses.PENDING.equals(e.statusCode) || AiReviewStatuses.UNCERTAIN.equals(e.statusCode))
                .count();
        int confirmed = AiDemoSeeder.BASE_CONFIRMED + (int) all.stream()
                .filter(e -> AiReviewStatuses.isConfirmedChain(e.statusCode)).count();
        int falsePositive = AiDemoSeeder.BASE_FALSE + (int) all.stream()
                .filter(e -> AiReviewStatuses.FALSE_POSITIVE.equals(e.statusCode)).count();
        int cameraFault = (int) all.stream().filter(e -> !"正常".equals(e.health)).count();
        return new AiMetrics(AiDemoSeeder.BASE_TODAY + (int) Math.max(0, simulated),
                pending, confirmed, falsePositive, cameraFault);
    }

    public AiFacets facets() {
        List<DemoAiEvent> all = repository.findAll();
        return new AiFacets(
                all.stream().map(e -> e.area).distinct().sorted().toList(),
                all.stream().map(e -> e.camera).distinct().sorted().toList());
    }

    public DemoAiEvent get(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("AI 事件不存在: " + id));
    }

    public List<CameraInfo> cameras() {
        return List.copyOf(cameras);
    }

    // ---------- 人工复核 ----------

    /** 确认违规：待复核/不确定 → 已确认违规，并创建关联 Alert（幂等防重复创建）。 */
    public DemoAiEvent confirm(String id, ReviewRequest body, String idemKey) {
        return mutate(id, idemKey, "reviewed",
                List.of(AiReviewStatuses.PENDING, AiReviewStatuses.UNCERTAIN), event -> {
                    String reviewer = pick(body == null ? null : body.reviewer());
                    event.statusCode = AiReviewStatuses.CONFIRMED;
                    event.reviewer = reviewer;
                    event.reviewTime = nowHms();
                    appendTimeline(event, reviewer + " 确认违规", "active", AiTimelineEventTypes.CONFIRMED);

                    // 已有关联 Alert（重复 confirm / 派单后回流）不重复创建
                    if (event.linkedAlertId == null) {
                        DemoAlert alert = alertService.createFromAi(toAlertDraft(event));
                        event.linkedAlertId = alert.id;
                        appendTimeline(event, "已生成安全告警 " + alert.id + "，进入统一处置主链", "done",
                                AiTimelineEventTypes.ALERT_LINKED);
                        log.info("AI 事件 {} 确认违规，关联告警 {}", id, alert.id);
                    }
                });
    }

    /** 标记误报：永不创建 Alert。 */
    public DemoAiEvent falsePositive(String id, FalsePositiveRequest body, String idemKey) {
        return mutate(id, idemKey, "reviewed",
                List.of(AiReviewStatuses.PENDING, AiReviewStatuses.UNCERTAIN), event -> {
                    String reviewer = pick(body == null ? null : body.reviewer());
                    String reason = body != null && body.reason() != null && !body.reason().isBlank()
                            ? body.reason() : "遮挡误判";
                    event.statusCode = AiReviewStatuses.FALSE_POSITIVE;
                    event.reviewer = reviewer;
                    event.reviewTime = nowHms();
                    event.falseReason = reason;
                    appendTimeline(event, "标记为误报（" + reason + "）", "active",
                            AiTimelineEventTypes.FALSE_POSITIVE);
                });
    }

    /** 暂不确定：进入人工复核队列，不生成 Alert。 */
    public DemoAiEvent uncertain(String id, UncertainRequest body, String idemKey) {
        return mutate(id, idemKey, "changed", List.of(AiReviewStatuses.PENDING), event -> {
            String reviewer = pick(body == null ? null : body.reviewer());
            event.statusCode = AiReviewStatuses.UNCERTAIN;
            event.reviewer = reviewer;
            event.reviewTime = nowHms();
            appendTimeline(event, "置信度不足，转入人工复核队列", "active",
                    AiTimelineEventTypes.UNCERTAIN);
        });
    }

    /**
     * AI 派单：无关联 Alert 时先创建 Alert 并复用 confirm→assign 主链；
     * 已有关联 Alert 时按其当前状态补齐确认后直接派单。不实现第二套派单系统。
     */
    public DemoAiEvent assign(String id, AiAssignRequest body, String idemKey) {
        return mutate(id, idemKey, "reviewed",
                List.of(AiReviewStatuses.PENDING, AiReviewStatuses.UNCERTAIN, AiReviewStatuses.CONFIRMED), event -> {
                    // 责任人以 USR code 为权威，姓名由主数据解析（F-02），不信任前端姓名。
                    DemoUser user = masterData.resolveUser(
                            body == null ? null : body.assigneeId(),
                            body == null ? null : body.assignee());
                    if (user == null) {
                        // 兼容旧前端缺省派单：默认王建国（USR-002）
                        user = masterData.user("USR-002");
                    }
                    if (user == null) {
                        throw ApiException.unprocessable("派单必须指定有效责任人（userId 或唯一姓名）");
                    }
                    String priority = "紧急".equals(body == null ? null : body.priority()) ? "紧急" : "普通";
                    String note = body == null ? null : body.note();
                    String reviewer = pick(body == null ? null : body.reviewer());

                    String alertId = event.linkedAlertId;
                    if (alertId == null) {
                        DemoAlert created = alertService.createFromAi(toAlertDraft(event));
                        alertId = created.id;
                        event.linkedAlertId = alertId;
                    }
                    // 补齐 Alert 主链：新建的 Alert 处于待确认，先 confirm 再 assign
                    DemoAlert current = alertService.get(alertId);
                    if (List.of(AlertStatuses.PENDING_CONFIRM, AlertStatuses.CONFIRMED)
                            .contains(current.statusCode)) {
                        alertService.confirm(alertId, new ConfirmRequest(reviewer), null);
                    }
                    AssignRequest req = new AssignRequest(user.name(), user.id(), user.name(),
                            priority, 15, null, null, note);
                    alertService.assign(alertId, req, null);

                    if (event.reviewer == null) {
                        event.reviewer = reviewer;
                        event.reviewTime = nowHms();
                    }
                    event.statusCode = AiReviewStatuses.ASSIGNED;
                    event.assigneeUserCode = user.id();
                    event.assignee = user.name();
                    event.assignmentPriority = priority;
                    event.assignmentNote = note;
                    event.processStatus = AlertStatuses.label(AlertStatuses.PENDING_PROCESS);
                    appendTimeline(event, "已派单至" + user.name() + "（" + priority + "优先级）", "active",
                            AiTimelineEventTypes.ASSIGNED);
                });
    }

    /** 标记处理中：同步推进关联 Alert 的 start（待处理 → 处理中）。 */
    public DemoAiEvent process(String id, ProcessRequest body, String idemKey) {
        return mutate(id, idemKey, "changed", List.of(AiReviewStatuses.ASSIGNED), event -> {
            String operator = pick(body == null ? null : body.operator());
            event.statusCode = AiReviewStatuses.PROCESSING;
            event.processStatus = AlertStatuses.label(AlertStatuses.PROCESSING);
            appendTimeline(event, (event.assignee == null ? "责任人" : event.assignee) + " 开始现场处置", "active",
                    AiTimelineEventTypes.PROCESSING);
            if (event.linkedAlertId != null) {
                DemoAlert alert = alertService.get(event.linkedAlertId);
                if (AlertStatuses.PENDING_PROCESS.equals(alert.statusCode)) {
                    alertService.start(event.linkedAlertId, new StartRequest(operator, operator), null);
                }
            }
        });
    }

    /** 关闭 AI 事件（不联动关闭 Alert，处置闭环仍以 Alert 主链为准）。 */
    public DemoAiEvent close(String id, ProcessRequest body, String idemKey) {
        return mutate(id, idemKey, "changed", List.of(AiReviewStatuses.PROCESSING), event -> {
            event.statusCode = AiReviewStatuses.CLOSED;
            event.processStatus = "已完成";
            appendTimeline(event, "处置完成，事件关闭", "done", AiTimelineEventTypes.CLOSED);
        });
    }

    // ---------- Demo 模拟 ----------

    /** 模拟新 AI 事件 / 低置信度 / 摄像头异常（SIMULATED，默认不生成 Alert）。 */
    public DemoAiEvent simulate(SimulateRequest body, String idemKey) {
        String kind = body == null || body.kind() == null ? "new" : body.kind();
        String[] createdId = new String[1];
        idempotency.process(idemKey, () -> {
            DemoAiEvent event = switch (kind) {
                case "low-confidence" -> buildLowConfidence();
                case "camera-fault" -> buildCameraFault();
                default -> buildNewEvent();
            };
            repository.save(event);
            notifier.changed("new", event);
            createdId[0] = event.id;
            log.info("模拟 AI 事件 kind={} id={}", kind, event.id);
            return "simulate:" + event.id;
        });
        // 幂等回放时 createdId[0] 为空，回退到该类别的最新一条
        if (createdId[0] == null) {
            createdId[0] = latestSimulatedId(kind);
        }
        return createdId[0] == null ? null : get(createdId[0]);
    }

    private String latestSimulatedId(String kind) {
        return repository.findAll().stream()
                .filter(e -> kind.equals("low-confidence") ? "翻越护栏".equals(e.type) && e.confidence < 70
                        : kind.equals("camera-fault") ? "摄像头异常".equals(e.type)
                        : "CAM-07".equals(e.camera))
                .map(e -> e.id).findFirst().orElse(null);
    }

    private DemoAiEvent buildNewEvent() {
        String time = nowHms();
        DemoAiEvent e = baseSim("未佩戴安全帽", "CAM-07", "装卸区 B 球机", "装卸区 B",
                96.2, 1.4, "PPE-Detection-v2.4.1", 85, "高", "正常", time);
        e.boxes = List.of(
                new AiBox("person", "PERSON", 97.4, 40, 44, 23, 44, "person"),
                new AiBox("helmet", "NO HELMET", 96.2, 45, 42, 13, 14, "violation"));
        e.rule = "装卸作业区域必须佩戴安全帽";
        e.judgeText = "连续 1.4 秒未检测到安全帽，置信度 96.2%，高于 85% 规则阈值。";
        e.relatedPerson = "现场待确认人员";
        return e;
    }

    private DemoAiEvent buildLowConfidence() {
        String time = nowHms();
        DemoAiEvent e = baseSim("翻越护栏", "CAM-05", "铁路线 B 枪机", "铁路装卸线 B",
                61, 0.9, "Fence-Guard-v1.8.2", 85, "中", "正常", time);
        e.statusCode = AiReviewStatuses.UNCERTAIN;
        e.boxes = List.of(
                new AiBox("person", "PERSON", 61.0, 55, 26, 21, 42, "person"),
                new AiBox("rail", "FENCE LINE", null, 12, 60, 76, 7, "zone"));
        e.rule = "禁止翻越装卸线安全护栏（阈值 85%）";
        e.judgeText = "动作与翻越模型部分匹配，但置信度仅 61%，低于 85% 阈值，不自动升级为严重告警，转人工复核。";
        e.relatedPerson = "现场待确认人员";
        e.timeline = List.of(
                new AiTimelineNode(time, "AI 检测到疑似翻越护栏", "done"),
                new AiTimelineNode(time, "置信度不足，转入人工复核队列", "active"));
        return e;
    }

    private DemoAiEvent buildCameraFault() {
        String time = nowHms();
        DemoAiEvent e = baseSim("摄像头异常", "CAM-03", "装卸区 A 球机", "装卸区 A",
                0, 0, "Camera-Health-v1.2.0", 0, "中", "画面质量下降", time);
        e.relatedDevice = "摄像头 CAM-03";
        e.rule = "画面清晰度低于可用阈值时降级";
        e.judgeText = "检测到画面遮挡 / 清晰度下降，AI 识别能力受限；摄像头异常不代表现场无违规。";
        e.timeline = List.of(
                new AiTimelineNode(time, "摄像头画面质量下降", "done"),
                new AiTimelineNode(time, "AI 能力受限，等待人工核查", "active"));
        return e;
    }

    private DemoAiEvent baseSim(String type, String camera, String cameraName, String area,
                                double confidence, double duration, String model, double threshold,
                                String risk, String health, String time) {
        DemoAiEvent e = new DemoAiEvent();
        e.id = nextAiId();
        e.type = type;
        e.camera = camera;
        e.cameraName = cameraName;
        e.area = area;
        e.confidence = confidence;
        e.durationSec = duration;
        e.model = model;
        e.threshold = threshold;
        e.time = time;
        e.statusCode = AiReviewStatuses.PENDING;
        e.riskCode = AiRiskLevels.fromLabel(risk);
        e.health = health;
        e.scene = AiDemoSeeder.sceneOf(type);
        e.boxes = new ArrayList<>();
        e.relatedPerson = "现场待确认人员";
        e.relatedDevice = "—";
        e.judgeText = "";
        e.timeline = List.of(
                new AiTimelineNode(time, "AI 检测到疑似" + type, "done"),
                new AiTimelineNode(time, "事件进入复核队列", "active"));
        OffsetDateTime now = OffsetDateTime.now(clock.withZone(ZONE));
        e.occurredAt = now;
        e.updatedAt = now;
        e.fresh = Boolean.TRUE;
        return e;
    }

    private String nextAiId() {
        return numberGenerator.nextAiEventNumber();
    }

    // ---------- 内部辅助 ----------

    /**
     * AI 命令统一入口：load → changer（可能跨聚合写 Alert）→ save AI → 发布事件。
     * Phase B：整个持久化阶段由 {@link LiveEventGate} 缓冲，Alert / AI 全部 save 成功后才统一广播，
     * 避免“alert.new 已推送、AI 事件尚未回写”的半流程广播；未来 JDBC 阶段 gate.flush 即 AFTER_COMMIT 替换点。
     */
    private DemoAiEvent mutate(String id, String idemKey, String op, List<String> allowed,
                               java.util.function.Consumer<DemoAiEvent> changer) {
        synchronized (id.intern()) {
            idempotency.process(idemKey, () -> {
                gate.buffer(() -> {
                    DemoAiEvent event = repository.findById(id)
                            .orElseThrow(() -> ApiException.notFound("AI 事件不存在: " + id));
                    if (!allowed.contains(event.statusCode)) {
                        throw ApiException.conflict("当前状态不允许执行该操作（当前状态："
                                + AiReviewStatuses.label(event.statusCode) + "）");
                    }
                    changer.accept(event);
                    event.updatedAt = OffsetDateTime.now(clock);
                    repository.save(event);
                    notifier.changed(op, event);
                });
                return op + ":" + id;
            });
            return get(id);
        }
    }

    private void appendTimeline(DemoAiEvent event, String text, String state) {
        appendTimeline(event, text, state, AiTimelineEventTypes.NOTE);
    }

    private void appendTimeline(DemoAiEvent event, String text, String state, String eventType) {
        List<AiTimelineNode> nodes = new ArrayList<>(event.timeline);
        nodes.replaceAll(n -> "active".equals(n.state())
                ? new AiTimelineNode(n.time(), n.text(), "done",
                        n.eventType(), n.sequenceNo()) : n);
        String hms = nowHms();
        int sequenceNo = nodes.stream()
                .mapToInt(n -> n.sequenceNo() == null ? 0 : n.sequenceNo()).max().orElse(0) + 1;
        nodes.add(new AiTimelineNode(hms, text, state, eventType, sequenceNo));
        event.timeline = List.copyOf(nodes);
    }

    /** AI 事件 → Alert 新建草稿（AI 风险等级 / 类型映射到统一告警口径）。 */
    private AlertService.NewAlertFromAi toAlertDraft(DemoAiEvent e) {
        Map<String, String[]> titleMap = Map.of(
                "未佩戴安全帽", new String[]{"作业人员未佩戴安全帽（AI 复核确认）", "未佩戴安全帽"},
                "翻越护栏", new String[]{"人员翻越安全护栏（AI 复核确认）", "翻越护栏"},
                "闯入危险区域", new String[]{"人员闯入危险区域（AI 复核确认）", "危险区域闯入"},
                "人员滞留", new String[]{"人员长时间滞留作业区域（AI 复核确认）", "人员滞留"},
                "摄像头异常", new String[]{"摄像头画面质量下降（AI 复核确认）", "视频设备异常"});
        String[] naming = titleMap.getOrDefault(e.type, new String[]{e.type + "（AI 复核确认）", e.type});
        String alertRiskCode = mapAlertRiskCode(e);
        String target = e.relatedPerson != null && !"—".equals(e.relatedPerson) ? e.relatedPerson : e.camera;
        List<DetectionBox> boxes = e.boxes.stream()
                .map(b -> new DetectionBox(b.id(), b.label(), b.score() == null ? 0.0 : b.score(),
                        (int) b.x(), (int) b.y(), (int) b.w(), (int) b.h(), b.tone()))
                .toList();
        AiEvidence evidence = AiEvidence.of(e.scene, boxes, e.confidence, e.model, e.camera, e.time);
        int duration = (int) Math.round(e.durationSec);
        return new AlertService.NewAlertFromAi(e.id, naming[0], naming[1], alertRiskCode, e.area, target,
                duration, null, null, e.model, null, evidence);
    }

    /** AI 事件类型 → Alert 四级风险 code：闯入/翻越/未戴帽为严重，滞留为预警，其余一般。 */
    private String mapAlertRiskCode(DemoAiEvent e) {
        return switch (e.type) {
            case "闯入危险区域", "翻越护栏", "未佩戴安全帽" -> RiskLevels.SEVERE;
            case "人员滞留" -> RiskLevels.WARNING;
            default -> RiskLevels.NORMAL;
        };
    }

    private String pick(String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return Optional.ofNullable(demoUser).map(DemoUserProperties::name).orElse("李娜");
    }

    private String nowHms() {
        return OffsetDateTime.now(clock.withZone(ZONE)).format(HMS);
    }
}
