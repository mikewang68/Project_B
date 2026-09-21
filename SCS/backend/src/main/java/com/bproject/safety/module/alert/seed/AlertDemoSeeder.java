package com.bproject.safety.module.alert.seed;

import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.AlertEvidence.AiEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.CollisionEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.DetectionBox;
import com.bproject.safety.module.alert.model.AlertEvidence.MetricEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.MetricItem;
import com.bproject.safety.module.alert.model.AlertEvidence.PersonnelEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.Point;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.LinkageStep;
import com.bproject.safety.module.alert.model.TimelineEvent;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 告警 Demo 初始化数据：与前端 frontend/src/mock/alertEvents.ts 的 12 条场景保持一致，
 * 保证后续前端移除 Mock 后页面视觉不发生明显变化。仅在存储为空时灌入。
 */
@Component
@Order(10)
public class AlertDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AlertDemoSeeder.class);
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final LocalDate DEMO_DATE = LocalDate.of(2026, 9, 4);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final com.bproject.safety.module.alert.repository.AlertRepository repository;
    private final Clock clock;
    private final com.bproject.safety.support.demo.DemoFeatureGuard guard;

    public AlertDemoSeeder(com.bproject.safety.module.alert.repository.AlertRepository repository, Clock clock,
                           com.bproject.safety.support.demo.DemoFeatureGuard guard) {
        this.repository = repository;
        this.clock = clock;
        this.guard = guard;
    }

    @Override
    public void run(String... args) {
        if (!guard.isSeedEnabled()) {
            return;
        }
        if (repository.count() > 0) {
            return;
        }
        List<DemoAlert> seeds = buildSeeds(clock);
        seeds.forEach(repository::save);
        log.info("告警 Demo 数据初始化完成：{} 条", seeds.size());
    }

    /** 构建 12 条种子（可独立用于测试）。 */
    public static List<DemoAlert> buildSeeds(Clock clock) {
        List<DemoAlert> list = new ArrayList<>();

        list.add(alert("ALM-20260904-001", "人员进入龙门吊作业区域", "紧急", "危险区域闯入", "13:21:08",
                "装卸区 A", "赵磊（P-1003）", "人员安全", "处理中", "安全员 王建国", 222L,
                "RULE-PER-001", "v3.3", 96, PersonnelEvidence.of(
                        List.of(p(12, 82), p(24, 70), p(38, 58), p(52, 44), p(63, 30)),
                        "装卸区 A · 龙门吊 G-CRANE-01 回转半径内", "吊装作业禁入区（红色边界）",
                        "BAND-1003", "在线 · 持续震动提醒中", "118 次/分"),
                true, linkageProgress(5, true),
                List.of(tl("13:21:08", "检测到人员进入危险区域", "done"), tl("13:21:09", "生成紧急告警", "done"),
                        tl("13:21:09", "现场声光提醒启动", "done"), tl("13:21:10", "人员手环震动提醒已发送", "done"),
                        tl("13:21:10", "通知安全员 王建国", "done"), tl("13:21:11", "通知调度员", "done"),
                        tl("13:21:14", "向 G-CRANE-01 发送减速请求", "done"),
                        tl("13:21:30", "安全员确认事件，王建国接单", "done"),
                        tl("13:21:31", "发起设备停机，等待 PLC 回执", "active")),
                "王建国", "13:21:30", "13:21:30", "紧急", 10, clock));

        list.add(alert("ALM-20260904-002", "作业人员未佩戴安全帽", "严重", "未佩戴安全帽", "13:18:52",
                "装卸区 B", "作业人员 P-1042", "AI违规", "待确认", "待分配", 752L,
                "RULE-AI-002", "v1.8", 3,
                AiEvidence.of("helmet", List.of(box("person", "PERSON", 97, 39, 44, 24, 44, "person"),
                        box("helmet", "NO HELMET", 94.8, 44, 42, 13, 14, "violation")),
                        94.8, "PPE-Detection-v2.4.1", "CAM-01", "13:18:52"),
                false, linkageTemplate(),
                List.of(tl("13:18:52", "AI 识别到未佩戴安全帽", "done"),
                        tl("13:18:53", "生成告警并进入待确认队列", "active")),
                null, null, null, null, 15, clock));

        list.add(alert("ALM-20260904-003", "设备间距小于安全阈值", "严重", "设备接近预警", "13:15:40",
                "箱区 B", "G-CRANE-01 / 转运车 VEH-08", "设备防碰撞", "待确认", "待分配", -266L,
                "RULE-DEV-003", "v2.4", 24,
                CollisionEvidence.of(4.2, 1.8, List.of(9.6, 8.4, 7.5, 6.8, 5.9, 5.1, 4.2),
                        "毫米波雷达正常 · 激光雷达正常", "当前制动距离 3.6m"),
                true, linkageTemplate(),
                List.of(tl("13:15:16", "设备间距进入 10m 预警圈", "done"),
                        tl("13:15:40", "间距跌破 6m 严重阈值，生成告警", "active")),
                null, null, null, null, 10, clock));

        list.add(alert("ALM-20260904-004", "人员长时间滞留作业通道", "预警", "人员滞留", "13:09:27",
                "箱区通道 C", "孙倩（P-1021）", "人员安全", "待派单", "待分配", 134L,
                "RULE-PER-003", "v1.6", 186,
                PersonnelEvidence.of(List.of(p(40, 50), p(42, 48), p(41, 49), p(40, 50), p(41, 49)),
                        "箱区通道 C（静止停留 186 秒）", "通道滞留预警区", "BAND-1021",
                        "在线 · 电量 64%", "86 次/分"),
                false, linkageTemplate(),
                List.of(tl("13:09:27", "滞留超过 30 秒阈值，生成预警", "done"),
                        tl("13:10:02", "安全员李娜核实事件，等待派单处置", "active")),
                "李娜", "13:10:02", null, null, 20, clock));

        list.add(alert("ALM-20260904-005", "人员翻越安全护栏", "预警", "翻越护栏", "12:58:11",
                "铁路装卸线 B", "外协人员 P-1077", "AI违规", "待处理", "安全员 王建国", 1085L,
                "RULE-PER-002", "v2.1", 2,
                AiEvidence.of("fence", List.of(box("person", "PERSON", 92, 55, 26, 21, 42, "person"),
                        box("rail", "FENCE LINE", 0, 12, 60, 76, 7, "zone")),
                        88.4, "Fence-Guard-v1.8.2", "CAM-05", "12:58:11"),
                false, linkageTemplate(),
                List.of(tl("12:58:11", "AI 识别到翻越护栏动作", "done"),
                        tl("12:59:03", "安全员确认事件", "done"),
                        tl("12:59:40", "事件已派发给王建国，等待接单", "active")),
                "李娜", "12:59:03", null, "普通", 30, clock));

        list.add(alert("ALM-20260904-006", "摄像头画面质量下降", "一般", "视频设备异常", "12:46:33",
                "装卸区 A", "摄像头 CAM-02", "设备异常", "处理中", "值班员 陈晓", 2110L,
                "RULE-AI-006", "v1.1", 905,
                MetricEvidence.device(List.of(
                        mi("清晰度评分", "38 / 100", "warn"), mi("丢帧率", "12.4%", "warn"),
                        mi("在线时长", "46 天", "ok"), mi("异常原因", "镜头遮挡 / 积灰", null)),
                        "画面清晰度持续低于可用阈值，AI 识别能力受限，不代表现场无违规。"),
                false, linkageTemplate(),
                List.of(tl("12:46:33", "摄像头健康巡检发现画面质量下降", "done"),
                        tl("12:48:10", "值班员陈晓接单，前往现场清洁镜头", "active")),
                "李娜", "12:47:02", "12:48:10", "普通", 60, clock));

        DemoAlert a7 = alert("ALM-20260904-007", "翻箱机运行区域人员闯入", "严重", "危险区域闯入", "12:31:19",
                "翻箱机区", "外协人员 P-1068 / TIP-03", "设备防碰撞", "待复核", "班长 刘志明", 400L,
                "RULE-PER-001", "v3.3", 41,
                CollisionEvidence.of(2.8, 0.6, List.of(7.2, 6.1, 5.0, 4.1, 3.5, 3.0, 2.8),
                        "毫米波雷达正常", "设备已制动停止"),
                true, firstNStepsSuccess(7),
                List.of(tl("12:31:19", "人员进入翻箱机运行区域", "done"),
                        tl("12:31:20", "生成严重告警并启动联动", "done"),
                        tl("12:31:21", "现场声光 / 手环 / 通知全部送达", "done"),
                        tl("12:31:25", "翻箱机自动减速", "done"),
                        tl("12:31:33", "设备停机，PLC 已确认", "done"),
                        tl("12:33:02", "班长刘志明确认并接单", "done"),
                        tl("12:38:47", "提交处置结果：人员已撤离、设备已停止，等待管理复核", "active")),
                "刘志明", "12:33:02", "12:33:02", "紧急", 15, clock);
        a7.linkageFinished = true;
        a7.treatment = new com.bproject.safety.module.alert.model.TreatmentRecord(
                List.of("人员已撤离危险区域", "设备已停止运行", "现场确认无遗留风险"), "风险已解除",
                "现场处置照片_123840.jpg（Mock）", "已对该外协人员进行现场安全教育", "12:38:47", "刘志明");
        list.add(a7);

        DemoAlert a8 = alert("ALM-20260904-008", "边缘节点磁盘使用率超过 85%", "一般", "系统资源告警", "11:42:06",
                "机房", "边缘节点 EDGE-03", "系统异常", "已关闭", "值班员 陈晓", null,
                "RULE-ALM-001", "v2.2", 1240,
                MetricEvidence.system(List.of(
                        mi("峰值使用率", "91%", "warn"), mi("清理后使用率", "62%", "ok"),
                        mi("CPU", "34%", "ok"), mi("内存", "58%", "ok")),
                        "历史视频缓存占满数据盘，清理后恢复正常。"),
                false, linkageTemplate(),
                List.of(tl("11:42:06", "磁盘使用率超过 85% 阈值", "done"),
                        tl("11:45:20", "陈晓接单处理", "done"),
                        tl("12:02:46", "清理历史缓存，使用率回落", "done"),
                        tl("12:05:11", "复核通过，事件关闭", "done")),
                "陈晓", "11:43:40", "11:45:20", "普通", null, clock);
        a8.treatment = new com.bproject.safety.module.alert.model.TreatmentRecord(
                List.of("现场确认无遗留风险"), "风险已解除", "磁盘清理记录_120246.txt（Mock）",
                "已调整缓存保留周期为 7 天", "12:02:46", "陈晓");
        a8.reviewUser = "李娜";
        a8.reviewTime = "12:05:11";
        list.add(a8);

        list.add(alert("ALM-20260904-009", "作业人员手环电量低", "预警", "穿戴设备异常", "11:36:50",
                "维修通道", "吴凯（P-1034）", "人员安全", "待确认", "待分配", 588L,
                "RULE-PER-003", "v1.6", 0,
                PersonnelEvidence.of(List.of(p(30, 60), p(33, 57), p(36, 54)),
                        "维修通道（移动中）", "常规作业区", "BAND-1034", "在线 · 电量 12%", "92 次/分"),
                false, linkageTemplate(),
                List.of(tl("11:36:50", "手环电量低于 15%，生成预警", "active")),
                null, null, null, null, 20, clock));

        DemoAlert a10 = alert("ALM-20260904-010", "两台龙门吊运行轨迹交汇", "严重", "设备交汇风险", "11:20:14",
                "箱区 A", "G-CRANE-01 / G-CRANE-02", "设备防碰撞", "处理中", "安全员 王建国", -72L,
                "RULE-DEV-009", "v1.5", 312,
                CollisionEvidence.of(5.4, 2.2, List.of(11.2, 9.8, 8.6, 7.4, 6.6, 5.9, 5.4),
                        "毫米波雷达正常 · 激光雷达正常", "当前制动距离 4.8m"),
                true, linkageProgress(4, false),
                List.of(tl("11:20:14", "轨迹交汇预测，生成预警", "done"),
                        tl("11:23:41", "风险持续升高，由预警升级为严重", "done"),
                        tl("11:24:02", "王建国接单处置", "done"),
                        tl("11:24:20", "联动执行至调度通知，继续处置中", "active")),
                "王建国", "11:24:02", "11:24:02", "紧急", 15, clock);
        a10.upgradedFromCode = RiskLevels.WARNING;
        a10.previousRiskLevelCode = RiskLevels.WARNING;
        list.add(a10);

        DemoAlert a11 = alert("ALM-20260904-011", "人员接近电子围栏边界", "一般", "围栏接近提醒", "10:58:32",
                "临时施工区域", "郑阳（P-1048）", "人员安全", "已关闭", "安全员 李娜", null,
                "RULE-PER-002", "v2.1", 48,
                PersonnelEvidence.of(List.of(p(18, 74), p(24, 68), p(30, 62)),
                        "已返回安全区域", "临时施工围栏", "BAND-1048", "在线 · 电量 78%", "88 次/分"),
                false, linkageTemplate(),
                List.of(tl("10:58:32", "人员距围栏边界小于 3m", "done"),
                        tl("10:59:20", "李娜确认并现场提醒", "done"),
                        tl("11:00:15", "人员撤离，风险解除，事件关闭", "done")),
                "李娜", "10:59:20", "10:59:20", "普通", null, clock);
        a11.treatment = new com.bproject.safety.module.alert.model.TreatmentRecord(
                List.of("人员已撤离危险区域"), "风险已解除", "无", "口头教育后返回岗位", "11:00:10", "李娜");
        a11.reviewUser = "李娜";
        a11.reviewTime = "11:00:15";
        list.add(a11);

        DemoAlert a12 = alert("ALM-20260904-012", "龙门吊制动油压异常", "严重", "机械设备异常", "10:22:47",
                "装卸区 A", "G-CRANE-02", "设备异常", "已关闭", "设备管理员 周海", null,
                "RULE-DEV-002", "v2.0", 1680,
                MetricEvidence.device(List.of(
                        mi("异常油压", "3.2 MPa", "danger"), mi("额定区间", "4.5–6.5 MPa", null),
                        mi("检修后油压", "5.4 MPa", "ok"), mi("处理方式", "更换密封件", null)),
                        "制动油压低于额定区间，停机检修后恢复。"),
                true, firstNStepsSuccess(6),
                List.of(tl("10:22:47", "制动油压跌破下限，生成严重告警", "done"),
                        tl("10:23:05", "联动减速并通知设备管理员", "done"),
                        tl("10:25:18", "周海接单，设备停机检修", "done"),
                        tl("10:50:47", "更换密封件，油压恢复", "done"),
                        tl("10:54:02", "复核通过，事件关闭", "done")),
                "周海", "10:23:40", "10:25:18", "紧急", null, clock);
        a12.linkageFinished = true;
        a12.treatment = new com.bproject.safety.module.alert.model.TreatmentRecord(
                List.of("设备已停止运行", "现场确认无遗留风险"), "风险已解除",
                "检修工单 WO-0904-03（Mock）", "已纳入本周设备保养计划", "10:50:47", "周海");
        a12.reviewUser = "刘志明";
        a12.reviewTime = "10:54:02";
        list.add(a12);

        return list;
    }

    // ---------- 装配辅助 ----------

    @SuppressWarnings("checkstyle:ParameterNumber")
    private static DemoAlert alert(String id, String title, String risk, String eventType, String time,
                                   String area, String target, String source, String status, String assignee,
                                   Long slaRemainingSec, String ruleId, String ruleVersion, int durationSec,
                                   AlertEvidence evidence, boolean linkageAvailable, List<LinkageStep> linkage,
                                   List<TimelineEvent> timeline, String confirmUser, String confirmTime,
                                   String acceptTime, String priority, Integer slaLimitMin, Clock clock) {
        DemoAlert a = new DemoAlert();
        a.id = id;
        a.title = title;
        // 种子中文等级 / 状态仅用于装配，落模型时统一转机器 code（Demo 种子一次性转换，非运行时判断）。
        a.riskCode = RiskLevels.fromLabel(risk);
        if (a.riskCode == null) {
            throw new IllegalStateException("种子风险等级非法: " + risk + " @ " + id);
        }
        a.eventType = eventType;
        a.time = time;
        a.area = area;
        a.target = target;
        a.source = source;
        a.statusCode = AlertStatuses.fromLabel(status);
        if (a.statusCode == null) {
            throw new IllegalStateException("种子状态非法: " + status + " @ " + id);
        }
        a.assignee = assignee;
        a.assigneeUserCode = resolveAssigneeCode(assignee);
        if (ruleId != null) {
            a.decisionSourceType = DecisionSources.RULE;
            a.decisionSourceCode = ruleId;
            a.decisionSourceVersion = ruleVersion;
        }
        a.ruleId = ruleId;
        a.ruleVersion = ruleVersion;
        a.durationSec = durationSec;
        a.evidence = evidence;
        a.linkageAvailable = linkageAvailable;
        a.linkage = linkage;
        a.timeline = stampSeedTimeline(timeline);
        a.confirmUser = confirmUser;
        a.confirmTime = confirmTime;
        a.acceptTime = acceptTime;
        a.priority = priority;
        a.slaLimitMin = slaLimitMin;
        a.occurredAt = OffsetDateTime.of(DEMO_DATE.atTime(java.time.LocalTime.parse(time, HMS)), OFFSET);
        a.updatedAt = OffsetDateTime.now(clock);
        if (slaRemainingSec != null) {
            a.slaRemainingSec = slaRemainingSec;
            a.slaDeadline = OffsetDateTime.now(clock).plusSeconds(slaRemainingSec);
        }
        return a;
    }

    private static final com.bproject.safety.support.masterdata.DemoMasterData MASTER =
            new com.bproject.safety.support.masterdata.DemoMasterData();

    /** 种子责任人展示串可能带岗位前缀（如“安全员 王建国”），按姓名后缀匹配用户 code。 */
    private static String resolveAssigneeCode(String assignee) {
        if (assignee == null || "待分配".equals(assignee)) {
            return null;
        }
        for (com.bproject.safety.support.masterdata.DemoMasterData.DemoUser u : MASTER.users()) {
            if (assignee.contains(u.name())) {
                return u.id();
            }
        }
        return null;
    }

    /**
     * Demo 种子历史时间线补机器事件类型与连续序号（仅种子装配期执行一次）。
     * 运行时业务节点的 eventType 由 AlertService 显式给出，不在此处做中文匹配。
     */
    private static List<TimelineEvent> stampSeedTimeline(List<TimelineEvent> raw) {
        List<TimelineEvent> out = new ArrayList<>();
        int seq = 1;
        for (TimelineEvent n : raw) {
            String type = seedEventType(n.text());
            out.add(TimelineEvent.event(n.at(), n.time(), n.text(), n.state(), type, seq++));
        }
        return out;
    }

    private static String seedEventType(String text) {
        if (text == null) {
            return AlertTimelineEventTypes.NOTE;
        }
        if (text.contains("提交处置结果")) {
            return AlertTimelineEventTypes.TREATMENT_SUBMITTED;
        }
        if (text.contains("复核驳回")) {
            return AlertTimelineEventTypes.REVIEW_REJECTED;
        }
        if (text.contains("复核通过") || text.contains("事件关闭") || text.contains("风险解除，事件关闭")) {
            return AlertTimelineEventTypes.CLOSED;
        }
        if (text.contains("升级为")) {
            return AlertTimelineEventTypes.ESCALATED;
        }
        if (text.contains("已派发")) {
            return AlertTimelineEventTypes.ASSIGNED;
        }
        if (text.contains("转派")) {
            return AlertTimelineEventTypes.TRANSFERRED;
        }
        if (text.contains("到达现场") || text.contains("已到达")) {
            return AlertTimelineEventTypes.ARRIVED;
        }
        if (text.contains("接单") || text.contains("赶赴现场")) {
            return AlertTimelineEventTypes.STARTED;
        }
        if (text.contains("确认事件") || text.contains("核实事件") || text.contains("李娜确认")
                || text.contains("确认并")) {
            return AlertTimelineEventTypes.CONFIRMED;
        }
        if (text.contains("人工接管")) {
            return AlertTimelineEventTypes.TAKEOVER;
        }
        if (text.contains("联动") || text.contains("PLC")) {
            return AlertTimelineEventTypes.LINKAGE_STARTED;
        }
        if (text.contains("生成") && (text.contains("告警") || text.contains("预警"))) {
            return AlertTimelineEventTypes.CREATED;
        }
        return AlertTimelineEventTypes.NOTE;
    }

    private static Point p(int x, int y) {
        return new Point(x, y);
    }

    private static MetricItem mi(String label, String value, String tone) {
        return new MetricItem(label, value, tone);
    }

    private static DetectionBox box(String id, String label, double score, int x, int y, int w, int h, String tone) {
        return new DetectionBox(id, label, score, x, y, w, h, tone);
    }

    private static TimelineEvent tl(String time, String text, String state) {
        OffsetDateTime at = OffsetDateTime.of(DEMO_DATE.atTime(java.time.LocalTime.parse(time, HMS)), OFFSET);
        return new TimelineEvent(time, at, text, state);
    }

    /** 七步联动模板，与前端 buildLinkageTemplate 一致。 */
    public static List<LinkageStep> linkageTemplate() {
        return List.of(
                LinkageStep.waitStep("sound-light", "现场声光提醒", "等待执行"),
                LinkageStep.waitStep("band", "人员手环提醒", "等待发送"),
                LinkageStep.waitStep("safety-officer", "安全员通知", "等待送达"),
                LinkageStep.waitStep("dispatcher", "调度员通知", "等待送达"),
                LinkageStep.waitStep("slowdown", "减速请求", "等待发送"),
                LinkageStep.waitStep("shutdown", "设备停机", "等待执行"),
                LinkageStep.waitStep("plc", "PLC 回执", "等待回执"));
    }

    private static String successDetail(String id) {
        return switch (id) {
            case "sound-light" -> "已执行";
            case "band" -> "已发送";
            case "safety-officer", "dispatcher" -> "已送达";
            case "slowdown" -> "已发送";
            case "shutdown" -> "已执行";
            case "plc" -> "已确认";
            default -> "已完成";
        };
    }

    /** 前 doneCount 步成功，其后可选 1 步 running，其余等待。 */
    public static List<LinkageStep> linkageProgress(int doneCount, boolean oneRunning) {
        List<LinkageStep> tpl = linkageTemplate();
        List<LinkageStep> out = new ArrayList<>();
        for (int i = 0; i < tpl.size(); i++) {
            LinkageStep s = tpl.get(i);
            if (i < doneCount) {
                out.add(s.withState("success", successDetail(s.id()), null));
            } else if (i == doneCount && oneRunning) {
                out.add(s.withState("running", "正在执行", null));
            } else {
                out.add(s);
            }
        }
        return out;
    }

    /** 前 n 步全部成功（用于已完成联动的历史告警）。 */
    public static List<LinkageStep> firstNStepsSuccess(int n) {
        List<LinkageStep> tpl = linkageTemplate();
        List<LinkageStep> out = new ArrayList<>();
        for (int i = 0; i < tpl.size(); i++) {
            LinkageStep s = tpl.get(i);
            out.add(i < n ? s.withState("success", successDetail(s.id()), null) : s);
        }
        return out;
    }
}
