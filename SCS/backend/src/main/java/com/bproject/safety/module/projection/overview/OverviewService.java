package com.bproject.safety.module.projection.overview;

import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.projection.overview.OverviewDtos.DistributionItem;
import com.bproject.safety.module.projection.overview.OverviewDtos.FeedItem;
import com.bproject.safety.module.projection.overview.OverviewDtos.FeedTimeline;
import com.bproject.safety.module.projection.overview.OverviewDtos.MapEquipment;
import com.bproject.safety.module.projection.overview.OverviewDtos.MapFence;
import com.bproject.safety.module.projection.overview.OverviewDtos.MapPerson;
import com.bproject.safety.module.projection.overview.OverviewDtos.MapPoint;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewDistribution;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewFeed;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewMap;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewSummary;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewTrend;
import com.bproject.safety.module.projection.overview.OverviewDtos.TrendPoint;
import com.bproject.safety.module.projection.shared.SafetyProjectionService;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 安全态势首页聚合（Projection）：不建独立业务库。
 * 告警口径全部实时聚合 Alert 权威源，AI 待复核来自 AiEventRepository；
 * 人员/设备/围栏坐标为 BASE DEMO MAP DATA，风险状态为 LIVE RISK OVERLAY（未关闭高风险 Alert 动态覆盖）。
 */
@Service
public class OverviewService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SafetyProjectionService projection;
    private final AiEventRepository aiEventRepository;
    private final AlertService alertService;
    private final PersonnelRepository personnelRepository;
    private final CollisionRepository collisionRepository;
    private final FenceRepository fenceRepository;

    public OverviewService(SafetyProjectionService projection,
                           AiEventRepository aiEventRepository,
                           AlertService alertService,
                           PersonnelRepository personnelRepository,
                           CollisionRepository collisionRepository,
                           FenceRepository fenceRepository) {
        this.projection = projection;
        this.aiEventRepository = aiEventRepository;
        this.alertService = alertService;
        this.personnelRepository = personnelRepository;
        this.collisionRepository = collisionRepository;
        this.fenceRepository = fenceRepository;
    }

    public OverviewSummary summary() {
        long pendingAi = aiEventRepository.findAll().stream()
                .filter(e -> AiReviewStatuses.PENDING.equals(e.statusCode) || AiReviewStatuses.UNCERTAIN.equals(e.statusCode))
                .count();
        return new OverviewSummary(
                SafetyProjectionService.DEMO_ON_DUTY,
                SafetyProjectionService.DEMO_DEVICE_ONLINE,
                SafetyProjectionService.DEMO_DEVICE_TOTAL,
                projection.activeCount(),
                projection.processingCount(),
                projection.countByRiskOpen("紧急"),
                projection.countByRiskOpen("严重"),
                pendingAi,
                SafetyProjectionService.DEMO_RISKY_DEVICES,
                true, true, true);
    }

    /** 首页态势地图：Demo 底图 + 真实未关闭高风险 Alert 风险覆盖。 */
    public OverviewMap map() {
        List<DemoAlert> openHighRisks = projection.allAlerts().stream()
                .filter(a -> !AlertStatuses.CLOSED.equals(a.statusCode))
                .filter(a -> com.bproject.safety.module.alert.model.RiskLevels.SEVERE.equals(a.riskCode)
                        || com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode)
                        || com.bproject.safety.module.alert.model.RiskLevels.WARNING.equals(a.riskCode))
                .toList();

        List<MapPerson> people = basePeople().stream()
                .map(p -> overlayPerson(p, openHighRisks))
                .toList();
        List<MapEquipment> equipment = baseEquipment().stream()
                .map(e -> overlayEquipment(e, openHighRisks))
                .toList();
        boolean liveOverlay = openHighRisks.stream().anyMatch(
                a -> com.bproject.safety.module.alert.model.RiskLevels.SEVERE.equals(a.riskCode)
                        || com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode));
        return new OverviewMap(true, liveOverlay, people, equipment, baseFences());
    }

    public OverviewFeed feed(int limit) {
        List<FeedItem> list = projection.recent(limit, 20).stream().map(this::toFeedItem).toList();
        return new OverviewFeed(list);
    }

    /** 首页告警详情：直接复用 Alert 权威详情（不复制详情逻辑），只投影为首页 Drawer 结构。 */
    public FeedItem alertDetail(String id) {
        return toFeedItem(alertService.get(id), Integer.MAX_VALUE);
    }

    public OverviewTrend trend(String range) {
        boolean full24 = "24h".equalsIgnoreCase(range);
        List<TrendPoint> points = (full24 ? projection.trend24h() : projection.trend7d()).stream()
                .map(t -> new TrendPoint(t.label(), t.total(), t.highRisk()))
                .toList();
        return new OverviewTrend(true, points);
    }

    public OverviewDistribution distribution() {
        List<DistributionItem> items = projection.riskDistribution().stream()
                .map(c -> new DistributionItem(c.type(), c.count()))
                .toList();
        return new OverviewDistribution(items);
    }

    /** 首页风险演示开关（SIMULATED，委托 AlertService，走真实存储与广播）。 */
    public void simulateRisk(boolean active) {
        alertService.demoRisk(active);
    }

    // ---------- 风险覆盖 ----------

    private MapPerson overlayPerson(MapPerson base, List<DemoAlert> highRisks) {
        String risk = base.risk();
        String state = base.state();
        String area = base.area();
        double x = base.x();
        double y = base.y();
        boolean overlay = false;
        for (DemoAlert a : highRisks) {
            boolean nameHit = a.target != null && a.target.contains(base.name());
            boolean areaHit = areaKey(a.area).contains(areaKey(base.area()));
            if (nameHit) {
                risk = "高风险";
                state = "danger";
                area = a.area;
                overlay = true;
                // 复刻旧演示：赵磊触发龙门吊风险时移动到禁区边缘
                if (AlertService.DEMO_RISK_ALERT_ID.equals(a.id) && "P-ZHAO".equals(base.id())) {
                    x = 57;
                    y = 26;
                }
            } else if (areaHit && !"danger".equals(state)
                    && (com.bproject.safety.module.alert.model.RiskLevels.SEVERE.equals(a.riskCode)
                            || com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode))) {
                risk = "关注";
                state = "warning";
                overlay = true;
            }
        }
        return new MapPerson(base.id(), base.name(), base.team(), base.status(), base.battery(),
                area, risk, state, x, y, overlay);
    }

    private MapEquipment overlayEquipment(MapEquipment base, List<DemoAlert> highRisks) {
        String state = base.state();
        boolean overlay = false;
        for (DemoAlert a : highRisks) {
            String text = (safe(a.target) + safe(a.title) + safe(a.eventType));
            boolean hit = switch (base.id()) {
                case "CRANE-01" -> text.contains("龙门吊") || text.contains("CRANE") || text.contains("吊装");
                case "TIP-02" -> text.contains("翻箱机") || text.contains("TIP");
                case "VEH-07", "VEH-08" -> text.contains("转运车") || text.contains("VEH") || text.contains("车辆");
                default -> false;
            };
            if (hit) {
                state = (com.bproject.safety.module.alert.model.RiskLevels.SEVERE.equals(a.riskCode)
                        || com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode))
                        ? "danger" : "warning";
                overlay = true;
            }
        }
        return new MapEquipment(base.id(), base.name(), base.type(), state, base.x(), base.y(), overlay);
    }

    private static String safe(String v) {
        return v == null ? "" : v;
    }

    private static String areaKey(String v) {
        return v == null ? "" : v.replace(" ", "").replace("作业", "");
    }

    // ---------- BASE DEMO MAP DATA（人员/设备/围栏基础坐标来自各感知模块 Demo 台账，真实感知接入后替换） ----------

    private List<MapPerson> basePeople() {
        return personnelRepository.findAll().stream()
                .map(p -> new MapPerson(p.id, p.name, p.team, p.getStatus(), p.battery, p.area,
                        p.getRisk(), p.state, p.x, p.y, false))
                .toList();
    }

    private List<MapEquipment> baseEquipment() {
        return collisionRepository.findAll().stream()
                .map(d -> new MapEquipment(d.id, d.name, equipmentTypeKey(d.type),
                        "正常".equals(d.radarStatus) ? "normal" : "warning", d.x, d.y, false))
                .toList();
    }

    private List<MapFence> baseFences() {
        return fenceRepository.findAll().stream()
                .filter(f -> !FenceStatuses.DISABLED.equals(f.statusCode))
                .map(f -> new MapFence(f.id, f.name, f.tone, f.area,
                        f.polygon.stream().map(pt -> new MapPoint(pt.x(), pt.y())).toList()))
                .toList();
    }

    /** 防碰撞设备中文类型 → 地图图标 key。 */
    private static String equipmentTypeKey(String type) {
        return switch (type) {
            case "龙门吊" -> "crane";
            case "翻箱机" -> "tipper";
            case "转运车辆" -> "vehicle";
            default -> "device";
        };
    }

    // ---------- Feed 投影 ----------

    private FeedItem toFeedItem(DemoAlert a) {
        return toFeedItem(a, 3);
    }

    private FeedItem toFeedItem(DemoAlert a, int timelineLimit) {
        List<TimelineEvent> nodes = a.timeline == null ? List.of() : a.timeline;
        List<TimelineEvent> tail = nodes.size() <= timelineLimit
                ? nodes : nodes.subList(nodes.size() - timelineLimit, nodes.size());
        List<FeedTimeline> timeline = tail.stream()
                .map(n -> new FeedTimeline(n.time(), n.text(), timelineDetail(n.state())))
                .toList();
        return new FeedItem(a.id, a.title, a.getRisk(), projection.summaryOf(a),
                a.occurredAt == null ? null : ISO.format(a.occurredAt),
                a.area, a.target, a.getStatus(), timeline);
    }

    private String timelineDetail(String state) {
        return switch (state) {
            case "active" -> "进行中";
            case "pending" -> "待执行";
            default -> "已完成";
        };
    }
}
