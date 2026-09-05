package com.bproject.safety.module.ai.seed;

import com.bproject.safety.module.ai.model.AiBox;
import com.bproject.safety.module.ai.model.AiTimelineNode;
import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.CameraInfo;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
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
 * AI 事件 Demo 初始化数据：与 frontend/src/mock/aiEvents.ts 的 10 条种子保持一致，
 * 保证前端移除 Mock 后页面视觉不发生明显变化。仅在存储为空时灌入。
 */
@Component
@Order(11)
public class AiDemoSeeder implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(AiDemoSeeder.class);
    private static final ZoneOffset OFFSET = ZoneOffset.ofHours(8);
    private static final LocalDate DEMO_DATE = LocalDate.of(2026, 9, 3);
    private static final DateTimeFormatter HMS = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 初始种子数量（指标历史基数计算依赖）。 */
    public static final int SEED_COUNT = 10;
    /** 顶部指标历史归档基数（对齐前端 AI_METRIC_BASE）。 */
    public static final int BASE_TODAY = 26;
    public static final int BASE_CONFIRMED = 8;
    public static final int BASE_FALSE = 8;

    private final AiEventRepository repository;
    private final Clock clock;

    public AiDemoSeeder(AiEventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Override
    public void run(String... args) {
        if (repository.count() > 0) {
            return;
        }
        List<DemoAiEvent> seeds = buildSeeds();
        seeds.forEach(repository::save);
        log.info("AI 事件 Demo 数据初始化完成：{} 条", seeds.size());
    }

    /** 摄像头健康台账（Demo）。 */
    public static List<CameraInfo> buildCameras() {
        return List.of(
                camera("CAM-01", "装卸区 B 球机", "装卸区 B", 98.2, "正常"),
                camera("CAM-02", "龙门吊下枪机", "龙门吊作业区", 41.5, "画面质量下降"),
                camera("CAM-03", "装卸区 A 球机", "装卸区 A", 46.8, "画面质量下降"),
                camera("CAM-04", "翻箱机区枪机", "翻箱机作业区", 97.4, "正常"),
                camera("CAM-05", "铁路线 B 枪机", "铁路装卸线 B", 96.9, "正常"),
                camera("CAM-06", "箱区通道球机", "箱区通道 C", 95.1, "正常"),
                camera("CAM-07", "装卸区 B 球机", "装卸区 B", 97.8, "正常"),
                camera("CAM-08", "维修通道枪机", "维修通道", 94.3, "正常"));
    }

    private static CameraInfo camera(String id, String name, String area, double quality, String state) {
        return new CameraInfo(id, name, area, !"离线".equals(state), quality, state, "2026-09-03T22:15:00+08:00");
    }

    /** 构建 10 条 AI 种子事件（可独立用于测试）。 */
    public List<DemoAiEvent> buildSeeds() {
        List<DemoAiEvent> list = new ArrayList<>();

        list.add(event("AI-E-20260903-026", "未佩戴安全帽", "CAM-03", "装卸区 A 球机", "装卸区 A",
                94.8, 3.2, "PPE-Detection-v2.4.1", 85, "22:14:08", AiReviewStatuses.PENDING, "高", "正常",
                "装卸作业区域必须佩戴安全帽", "作业人员 P-1042", "龙门吊 G-CRANE-01",
                "连续 3.2 秒识别到人员头部未检测到安全帽，置信度高于 85% 规则阈值。",
                List.of(person(97, 39, 44, 24, 44), box("helmet", "NO HELMET", 94.8, 44, 42, 13, 14, "violation")),
                null, null, null, null, null, null, null));

        list.add(event("AI-E-20260903-025", "翻越护栏", "CAM-05", "铁路线 B 枪机", "铁路装卸线 B",
                88.4, 2.1, "Fence-Guard-v1.8.2", 85, "22:09:41", AiReviewStatuses.PENDING, "高", "正常",
                "禁止翻越装卸线安全护栏", "外协人员 P-1077", "—",
                "识别到人员跨越护栏边界的连续动作，姿态轨迹与翻越行为模型匹配。",
                List.of(person(92, 55, 26, 21, 42), box("rail", "FENCE LINE", null, 12, 60, 76, 7, "zone")),
                null, null, null, null, null, null, null));

        list.add(event("AI-E-20260903-024", "闯入危险区域", "CAM-02", "龙门吊下枪机", "龙门吊作业区",
                91.2, 5.6, "Zone-Intrusion-v2.1.0", 85, "22:05:17", AiReviewStatuses.PENDING, "高", "画面质量下降",
                "吊装作业半径内禁止人员进入", "作业人员 P-1031", "龙门吊 G-CRANE-02",
                "人员进入吊装回转半径危险区域并持续停留，当前摄像头画面质量下降，建议结合现场确认。",
                List.of(box("zone", "DANGER ZONE", null, 28, 22, 48, 58, "zone"), person(91.2, 45, 40, 17, 32)),
                null, null, null, null, null, null, null));

        list.add(event("AI-E-20260903-023", "人员滞留", "CAM-06", "箱区通道球机", "箱区通道 C",
                76.5, 42, "Linger-Detect-v1.5.3", 70, "21:58:02", AiReviewStatuses.PENDING, "中", "正常",
                "通道区域滞留超过 30 秒预警", "作业人员 P-1056", "—",
                "人员在通道区域静止滞留超过 30 秒阈值，需确认是否为正常作业停留。",
                List.of(box("zone", "LINGER ZONE", null, 24, 18, 52, 62, "zone"), person(82.1, 42, 36, 17, 34)),
                null, null, null, null, null, null, null));

        list.add(event("AI-E-20260903-022", "摄像头异常", "CAM-03", "装卸区 A 球机", "装卸区 A",
                0, 0, "Camera-Health-v1.2.0", 0, "21:52:36", AiReviewStatuses.PENDING, "中", "画面质量下降",
                "画面清晰度低于可用阈值时降级", "—", "摄像头 CAM-03",
                "检测到画面遮挡 / 清晰度下降，AI 识别能力受限；摄像头异常不代表现场无违规。",
                List.of(), null, null, null, null, null, null, null));

        list.add(event("AI-E-20260903-021", "未佩戴安全帽", "CAM-01", "装卸区 B 球机", "装卸区 B",
                96.1, 2.8, "PPE-Detection-v2.4.1", 85, "21:46:55", AiReviewStatuses.CONFIRMED, "高", "正常",
                "装卸作业区域必须佩戴安全帽", "作业人员 P-1024", "翻箱机 TIP-02",
                "连续 2.8 秒未检测到安全帽，特征稳定。",
                List.of(person(96.9, 40, 45, 23, 43), box("helmet", "NO HELMET", 96.1, 45, 43, 13, 13, "violation")),
                "李娜", "21:48:12", null, null, null, null, null));

        list.add(event("AI-E-20260903-020", "闯入危险区域", "CAM-04", "翻箱机区枪机", "翻箱机作业区",
                93.7, 4.1, "Zone-Intrusion-v2.1.0", 85, "21:37:20", AiReviewStatuses.CONFIRMED, "高", "正常",
                "设备运行区域禁止人员进入", "外协人员 P-1068", "翻箱机 TIP-03",
                "人员进入设备运行区域，轨迹与危险区重叠 4.1 秒。",
                List.of(box("zone", "DANGER ZONE", null, 30, 24, 46, 56, "zone"), person(94.2, 47, 42, 16, 31)),
                "李娜", "21:39:02", null, null, null, null, null));

        list.add(event("AI-E-20260903-016", "未佩戴安全帽", "CAM-01", "装卸区 B 球机", "装卸区 B",
                92.3, 3.6, "PPE-Detection-v2.4.1", 85, "20:58:44", AiReviewStatuses.CONFIRMED, "高", "正常",
                "装卸作业区域必须佩戴安全帽", "作业人员 P-1019", "转运车辆 VEH-08",
                "未检测到安全帽持续 3.6 秒，置信度高于阈值。",
                List.of(person(93.5, 42, 44, 22, 42), box("helmet", "NO HELMET", 92.3, 47, 42, 12, 13, "violation")),
                "王建国", "21:01:18", null, null, null, null, null));

        list.add(event("AI-E-20260903-018", "人员滞留", "CAM-08", "维修通道枪机", "维修通道",
                71.0, 68, "Linger-Detect-v1.5.3", 70, "21:21:09", AiReviewStatuses.FALSE_POSITIVE, "低", "正常",
                "通道区域滞留超过 30 秒预警", "维修人员 P-1007", "—",
                "逆光下将固定工器具误判为滞留人员。",
                List.of(box("zone", "LINGER ZONE", null, 24, 18, 52, 62, "zone"), person(71, 44, 38, 16, 32)),
                "李娜", "21:24:40", "光照问题", null, null, null, null));

        DemoAiEvent assigned = event("AI-E-20260903-017", "翻越护栏", "CAM-05", "铁路线 B 枪机", "铁路装卸线 B",
                95.4, 3.4, "Fence-Guard-v1.8.2", 85, "21:12:33", AiReviewStatuses.ASSIGNED, "高", "正常",
                "禁止翻越装卸线安全护栏", "外协人员 P-1071", "—",
                "翻越护栏动作完整，模型匹配度高。",
                List.of(person(95, 54, 25, 22, 43), box("rail", "FENCE LINE", null, 12, 60, 76, 7, "zone")),
                "李娜", "21:14:05", null, "安全员 王建国", "紧急", "待处理", "已电话通知现场劝阻");
        list.add(assigned);

        return list;
    }

    @SuppressWarnings("checkstyle:ParameterNumber")
    private DemoAiEvent event(String id, String type, String camera, String cameraName, String area,
                              double confidence, double durationSec, String model, double threshold,
                              String time, String status, String risk, String health,
                              String rule, String relatedPerson, String relatedDevice, String judgeText,
                              List<AiBox> boxes, String reviewer, String reviewTime, String falseReason,
                              String assignee, String assignmentPriority, String processStatus,
                              String assignmentNote) {
        DemoAiEvent e = new DemoAiEvent();
        e.id = id;
        e.type = type;
        e.camera = camera;
        e.cameraName = cameraName;
        e.area = area;
        e.confidence = confidence;
        e.durationSec = durationSec;
        e.model = model;
        e.threshold = threshold;
        e.time = time;
        e.status = status;
        e.risk = risk;
        e.health = health;
        e.scene = sceneOf(type);
        e.rule = rule;
        e.relatedPerson = relatedPerson;
        e.relatedDevice = relatedDevice;
        e.judgeText = judgeText;
        e.boxes = new ArrayList<>(boxes);
        e.reviewer = reviewer;
        e.reviewTime = reviewTime;
        e.falseReason = falseReason;
        e.assignee = assignee;
        e.assignmentPriority = assignmentPriority;
        e.processStatus = processStatus;
        e.assignmentNote = assignmentNote;
        e.occurredAt = OffsetDateTime.of(DEMO_DATE.atTime(LocalTime.parse(time, HMS)), OFFSET);
        e.updatedAt = OffsetDateTime.now(clock);
        e.timeline = buildTimeline(time, type, status, reviewTime == null ? "22:16:03" : reviewTime,
                falseReason, assignee);
        return e;
    }

    /** 与前端 buildTimeline 同构的时间线装配。 */
    private static List<AiTimelineNode> buildTimeline(String time, String typeText, String status,
                                                      String reviewTime, String falseReason, String assignee) {
        List<AiTimelineNode> nodes = new ArrayList<>();
        nodes.add(new AiTimelineNode(time, "AI 检测到疑似" + typeText, "done"));
        nodes.add(new AiTimelineNode(addSec(time, 1), "事件进入复核队列", "done"));

        switch (status) {
            case AiReviewStatuses.PENDING -> nodes.add(new AiTimelineNode(addSec(time, 2), "等待安全员人工复核", "active"));
            case AiReviewStatuses.UNCERTAIN -> {
                nodes.add(new AiTimelineNode(addSec(time, 64), "安全员查看事件", "done"));
                nodes.add(new AiTimelineNode(reviewTime, "置信度不足，转入人工复核队列", "active"));
            }
            case AiReviewStatuses.FALSE_POSITIVE -> {
                nodes.add(new AiTimelineNode(addSec(time, 64), "安全员查看事件", "done"));
                nodes.add(new AiTimelineNode(reviewTime,
                        "标记为误报（" + (falseReason == null ? "遮挡误判" : falseReason) + "）", "active"));
            }
            case AiReviewStatuses.CONFIRMED -> {
                nodes.add(new AiTimelineNode(addSec(time, 64), "安全员查看事件", "done"));
                nodes.add(new AiTimelineNode(reviewTime, "确认该事件为真实违规", "done"));
                nodes.add(new AiTimelineNode(reviewTime, "等待派单处置", "active"));
            }
            case AiReviewStatuses.ASSIGNED, AiReviewStatuses.PROCESSING -> {
                nodes.add(new AiTimelineNode(addSec(time, 64), "安全员查看事件", "done"));
                nodes.add(new AiTimelineNode(reviewTime, "确认该事件为真实违规", "done"));
                String dispatchText = assignee == null ? "已派单至安全员 王建国" : "已派单至" + assignee;
                if (AiReviewStatuses.PROCESSING.equals(status)) {
                    nodes.add(new AiTimelineNode(addSec(reviewTime, 17), dispatchText, "done"));
                    nodes.add(new AiTimelineNode(addSec(reviewTime, 120), "责任人现场处置中", "active"));
                } else {
                    nodes.add(new AiTimelineNode(addSec(reviewTime, 17), dispatchText, "active"));
                }
            }
            case AiReviewStatuses.CLOSED -> {
                nodes.add(new AiTimelineNode(reviewTime, "确认该事件为真实违规", "done"));
                nodes.add(new AiTimelineNode(addSec(reviewTime, 17), "处置完成，事件关闭", "done"));
            }
            default -> {
            }
        }
        return nodes;
    }

    private static String addSec(String time, int delta) {
        String[] parts = time.split(":");
        int total = (Integer.parseInt(parts[0]) * 3600 + Integer.parseInt(parts[1]) * 60 + Integer.parseInt(parts[2]) + delta) % 86400;
        return String.format("%02d:%02d:%02d", total / 3600, (total / 60) % 60, total % 60);
    }

    private static AiBox person(double score, double x, double y, double w, double h) {
        return box("person", "PERSON", score, x, y, w, h, "person");
    }

    private static AiBox box(String id, String label, Double score, double x, double y, double w, double h, String tone) {
        return new AiBox(id, label, score, x, y, w, h, tone);
    }

    /** 与前端 sceneOf 一致。 */
    public static String sceneOf(String type) {
        return switch (type) {
            case "未佩戴安全帽" -> "helmet";
            case "翻越护栏" -> "fence";
            case "闯入危险区域" -> "intrusion";
            case "人员滞留" -> "linger";
            default -> "camera";
        };
    }
}
