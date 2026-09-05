package com.bproject.safety.module.personnel.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.geo.Polygon2D;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.PersonnelEvidence;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.TrackPoint;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 人员定位业务服务（Backend Demo）：列表筛选 / 详情 / 轨迹 / 实时位置 / 手环提醒 / 异常模拟。
 *
 * <p>人员属于“感知输入”，真正需要处置的越界风险统一通过 {@link AlertService#createRiskAlert}
 * 进入 Alert 主链，不建立人员独立告警库；同一人员+同一围栏的未关闭风险按去重键不重复建单。</p>
 */
@Service
public class PersonnelService {

    private static final DateTimeFormatter HM = DateTimeFormatter.ofPattern("HH:mm");

    private final PersonnelRepository repository;
    private final FenceRepository fenceRepository;
    private final AlertRepository alertRepository;
    private final AlertService alertService;
    private final DomainLivePublisher publisher;
    private final Clock clock;

    public PersonnelService(PersonnelRepository repository, FenceRepository fenceRepository,
                            AlertRepository alertRepository, AlertService alertService,
                            DomainLivePublisher publisher, Clock clock) {
        this.repository = repository;
        this.fenceRepository = fenceRepository;
        this.alertRepository = alertRepository;
        this.alertService = alertService;
        this.publisher = publisher;
        this.clock = clock;
    }

    // ---------- 查询 ----------

    public PersonnelList list(String keyword, String team, String area, String state,
                              String bracelet, Boolean onlyAbnormal) {
        String kw = keyword == null ? "" : keyword.trim().toLowerCase();
        List<DemoPersonnel> filtered = repository.findAll().stream().filter(p -> {
            boolean kwOk = kw.isEmpty() || (p.name + p.jobNo).toLowerCase().contains(kw);
            boolean teamOk = team == null || team.isBlank() || team.equals(p.team);
            boolean areaOk = area == null || area.isBlank() || area.equals(p.area);
            boolean stateOk = state == null || state.isBlank()
                    || ("异常".equals(state) ? !"正常".equals(p.risk) : "正常".equals(p.risk));
            boolean bandOk = bracelet == null || bracelet.isBlank() || bracelet.equals(p.braceletStatus);
            boolean abnormalOk = onlyAbnormal == null || !onlyAbnormal || !"正常".equals(p.risk);
            return kwOk && teamOk && areaOk && stateOk && bandOk && abnormalOk;
        }).toList();
        List<DemoPersonnel> all = repository.findAll();
        long online = all.stream().filter(p -> "在线".equals(p.status)).count();
        long abnormal = all.stream().filter(p -> !"正常".equals(p.risk)).count();
        long bandOffline = all.stream().filter(p -> "离线".equals(p.braceletStatus)).count();
        long lowBattery = all.stream().filter(p -> "低电量".equals(p.braceletStatus) || p.battery < 20).count();
        return new PersonnelList(
                new Stats((int) online, (int) abnormal, (int) bandOffline, (int) lowBattery),
                filtered);
    }

    public DemoPersonnel get(String id) {
        DemoPersonnel p = require(id);
        // 关联未关闭告警只回传编号摘要，不复制完整 Alert
        List<String> active = alertRepository.findAll().stream()
                .filter(a -> !AlertStatuses.CLOSED.equals(a.status))
                .filter(a -> a.target != null && (a.target.contains(p.name) || a.target.contains(p.id)))
                .map(a -> a.id).toList();
        p.activeAlertIds = active;
        return p;
    }

    public List<TrackPoint> track(String id) {
        DemoPersonnel p = require(id);
        // 固定轨迹样本：以当前位置为终点，确定性回退（不接真实定位基站）
        double[][] rel = {{-13, 13}, {-6, 12}, {1, 10}, {9, 18}, {18, 22},
                {23, 13}, {14, 4}, {6, 1}, {0, 0}};
        OffsetDateTime end = OffsetDateTime.now(clock);
        List<TrackPoint> points = new ArrayList<>();
        for (int i = 0; i < rel.length; i++) {
            OffsetDateTime t = end.minusMinutes((rel.length - 1 - i) * 3L);
            points.add(new TrackPoint(round(p.x + rel[i][0]), round(p.y + rel[i][1]), t.format(HM)));
        }
        return points;
    }

    public List<LivePosition> live(String area) {
        return repository.findAll().stream()
                .filter(p -> "在线".equals(p.status))
                .filter(p -> area == null || area.isBlank() || area.equals(p.area))
                .map(p -> new LivePosition(p.id, p.x, p.y, p.area, p.battery, p.risk, p.state,
                        OffsetDateTime.now(clock).toString()))
                .toList();
    }

    public List<DemoAlert> alerts(String id, Integer limit) {
        DemoPersonnel p = require(id);
        int size = limit == null || limit <= 0 ? 10 : Math.min(limit, 50);
        return alertRepository.findAll().stream()
                .filter(a -> a.target != null && (a.target.contains(p.name) || a.target.contains(p.id)))
                .limit(size).toList();
    }

    public RemindResult remind(String id, String message) {
        DemoPersonnel p = require(id);
        return new RemindResult(true, OffsetDateTime.now(clock).toString(),
                "已向 " + p.name + " 的手环 " + p.braceletId + " 发送震动提醒"
                        + (message == null || message.isBlank() ? "" : "：" + message));
    }

    // ---------- 异常模拟（SIMULATED） ----------

    public DemoPersonnel simulateAbnormal(String id, String kind) {
        String targetId = (id == null || id.isBlank()) ? "P-ZHAO" : id;
        DemoPersonnel p = require(targetId);
        String k = kind == null ? "lowBattery" : kind;
        switch (k) {
            case "restore" -> restore(p);
            case "offline" -> {
                p.status = "离线";
                p.braceletStatus = "离线";
                p.positioningQuality = "无信号";
                p.risk = "关注";
                p.state = "offline";
                p.battery = 0;
                p.lastUpdated = "刚刚";
            }
            case "lowBattery" -> {
                p.battery = 8;
                p.braceletStatus = "低电量";
                p.risk = "关注";
                p.state = "warning";
                p.lastUpdated = "刚刚";
            }
            case "intrusion" -> intrude(p);
            default -> throw ApiException.unprocessable("不支持的模拟类型: " + k);
        }
        p.updatedAt = OffsetDateTime.now(clock);
        repository.save(p);
        publishMoved(p);
        return p;
    }

    /** 人员进入已生效危险围栏：Point-In-Polygon 命中后经 AlertService 建单（带去重）。 */
    private void intrude(DemoPersonnel p) {
        DemoFence fence = fenceRepository.findAll().stream()
                .filter(f -> FenceStatuses.EFFECTIVE.equals(f.status))
                .filter(f -> "危险区域".equals(f.kind) || "预警区域".equals(f.kind))
                .findFirst()
                .orElseThrow(() -> ApiException.unprocessable("当前没有已生效的危险围栏可供越界演示"));
        double[] inside = interiorPoint(fence.polygon);
        p.x = inside[0];
        p.y = inside[1];
        p.area = fence.area;
        p.risk = "高风险";
        p.state = "danger";
        p.alertsToday += 1;
        p.lastUpdated = "刚刚";
        String dedup = "PERSON_INTRUSION:" + p.id + ":" + fence.id;
        String risk = "危险区域".equals(fence.kind) ? fence.riskLevel : "预警";
        DemoAlert alert = alertService.createRiskAlert(new AlertService.NewRiskAlert(
                "人员安全", dedup,
                p.name + "进入" + fence.name, "人员越界", risk, fence.area,
                p.name + "（" + p.jobNo + "）", "RULE-PER-001", fence.version, 0,
                PersonnelEvidence.of(List.of(new AlertEvidence.Point((int) p.x, (int) p.y)),
                        fence.area + " · 围栏内", fence.name, p.braceletId, "在线 · 持续震动提醒中", "—"),
                p.name + " 进入电子围栏 " + fence.name + " 范围",
                "Point-In-Polygon 判定命中，生成人员越界告警"));
        p.activeAlertIds = List.of(alert.id);
    }

    private void restore(DemoPersonnel p) {
        p.battery = p.baseBattery;
        p.braceletStatus = p.baseBraceletStatus;
        p.positioningQuality = p.basePositioningQuality;
        p.status = p.baseStatus;
        p.risk = p.baseRisk;
        p.state = p.baseState;
        p.area = p.baseArea;
        p.x = p.baseX;
        p.y = p.baseY;
        p.lastUpdated = "刚刚";
    }

    private void publishMoved(DemoPersonnel p) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("personnelId", p.id);
        data.put("x", round(p.x));
        data.put("y", round(p.y));
        data.put("area", p.area);
        data.put("battery", p.battery);
        data.put("risk", p.risk);
        data.put("state", p.state);
        publisher.publish(LiveEventTypes.PERSON_MOVED, data);
    }

    private DemoPersonnel require(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("人员不存在: " + id));
    }

    /** 取多边形包围盒中心并向内部微移，保证落在围栏内（矩形围栏足够；Demo 不做复杂凹多边形）。 */
    private double[] interiorPoint(List<FencePoint> polygon) {
        double minX = polygon.stream().mapToDouble(FencePoint::x).min().orElse(50);
        double maxX = polygon.stream().mapToDouble(FencePoint::x).max().orElse(50);
        double minY = polygon.stream().mapToDouble(FencePoint::y).min().orElse(50);
        double maxY = polygon.stream().mapToDouble(FencePoint::y).max().orElse(50);
        double cx = (minX + maxX) / 2;
        double cy = (minY + maxY) / 2;
        List<double[]> coords = polygon.stream().map(FencePoint::xy).toList();
        if (Polygon2D.contains(cx, cy, coords)) {
            return new double[]{round(cx), round(cy)};
        }
        // 兜底：网格搜索第一个内部点
        for (double gx = minX; gx <= maxX; gx += 2) {
            for (double gy = minY; gy <= maxY; gy += 2) {
                if (Polygon2D.contains(gx, gy, coords)) {
                    return new double[]{round(gx), round(gy)};
                }
            }
        }
        return new double[]{round(cx), round(cy)};
    }

    private static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }

    // ---------- DTO ----------

    public record Stats(int online, int abnormal, int bandOffline, int lowBattery) {
    }

    public record PersonnelList(Stats stats, List<DemoPersonnel> list) {
    }

    public record LivePosition(String id, double x, double y, String area, int battery,
                               String risk, String state, String ts) {
    }

    public record RemindResult(boolean sent, String time, String message) {
    }
}
