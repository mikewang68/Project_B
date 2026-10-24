package com.bproject.safety.module.projection.shared;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.projection.shared.ProjectionDtos.DeviceHealth;
import com.bproject.safety.module.projection.shared.ProjectionDtos.RiskCount;
import com.bproject.safety.module.projection.shared.ProjectionDtos.TrendPoint;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 安全态势共享聚合：大屏 / 安全态势首页统一从这里取数，禁止复制两套分布 / 计数 / Feed 逻辑。
 *
 * <p>所有告警口径实时来自 {@link AlertRepository}（Alert 唯一权威事件源）；
 * 在岗人数、设备台账、历史趋势曲线暂无后端来源，使用显式 demo 标记的 Demo 聚合。</p>
 */
@Service
public class SafetyProjectionService {

    // ---- 暂无真实后端来源的台账常量（Demo aggregation） ----
    public static final int DEMO_ON_DUTY = 128;
    public static final int DEMO_DEVICE_ONLINE = 36;
    public static final int DEMO_DEVICE_TOTAL = 38;
    public static final int DEMO_RISKY_DEVICES = 2;

    private final AlertRepository repository;

    public SafetyProjectionService(AlertRepository repository) {
        this.repository = repository;
    }

    public List<DemoAlert> allAlerts() {
        return repository.findAll();
    }

    /** 告警总量（D-2 指标用：count() 直读，不加载聚合列表）。 */
    public long allAlertCount() {
        return repository.count();
    }

    /** 未关闭的活动告警数（Step 8：存储侧 GROUP BY 计数，不再全量加载）。 */
    public long activeCount() {
        return repository.countByStatus().entrySet().stream()
                .filter(e -> !AlertStatuses.CLOSED.equals(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    /** 处置中口径：处理中 / 待复核 / 已升级。 */
    private static final java.util.Set<String> IN_HANDLING = java.util.Set.of(
            AlertStatuses.PROCESSING, AlertStatuses.PENDING_REVIEW, AlertStatuses.ESCALATED);

    public long processingCount() {
        return repository.countByStatus().entrySet().stream()
                .filter(e -> IN_HANDLING.contains(e.getKey()))
                .mapToLong(Map.Entry::getValue)
                .sum();
    }

    public long countByRiskOpen(String riskCodeOrLabel) {
        String riskCode = com.bproject.safety.module.alert.model.RiskLevels.normalize(riskCodeOrLabel);
        return repository.countOpenByRisk().getOrDefault(riskCode, 0L);
    }

    public long closedCount() {
        return repository.countByStatus().getOrDefault(AlertStatuses.CLOSED, 0L);
    }

    /** 风险类型分布（按数量倒序，存储侧 GROUP BY 计数）。 */
    public List<RiskCount> riskDistribution() {
        return repository.countByEventType().entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .map(e -> new RiskCount(e.getKey(), e.getValue().intValue()))
                .toList();
    }

    /** 最近事件（按发生时间倒序，分页通道 SQL LIMIT 截断）。 */
    public List<DemoAlert> recent(int limit, int max) {
        int size = limit <= 0 ? 12 : Math.min(limit, max);
        return repository.page(new com.bproject.safety.module.alert.repository.AlertQuery(
                null, null, null, null, null, null, null, null, null, null, 1, size)).list();
    }

    /** 事件摘要（联动失败优先提示人工接管）。 */
    public String summaryOf(DemoAlert a) {
        if (a.linkageFailed) {
            return "PLC 回执超时，需人工接管";
        }
        return a.eventType == null ? a.title : a.eventType;
    }

    /** 设备健康台账（Demo 聚合常量）。 */
    public List<DeviceHealth> deviceHealth() {
        return List.of(
                new DeviceHealth("摄像头", 22, 24, true),
                new DeviceHealth("雷达", 8, 8, true),
                new DeviceHealth("定位基站", 12, 12, true),
                new DeviceHealth("边缘节点", 4, 4, true));
    }

    /** 近 24 小时趋势：暂无时序库来源，Demo 固定曲线（demo=true）。 */
    public List<TrendPoint> trend24h() {
        int[] totals = {2, 1, 1, 0, 1, 0, 1, 2, 3, 4, 3, 5, 4, 6, 5, 7, 6, 8, 7, 9, 6, 5, 4, 3};
        int[] high = {0, 0, 0, 0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 2, 1, 2, 1, 2, 1, 3, 2, 1, 1, 1};
        java.util.List<TrendPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < totals.length; i++) {
            points.add(new TrendPoint(String.format("%02d:00", i), totals[i], high[i], true));
        }
        return points;
    }

    /** 近 7 日趋势（Demo 固定曲线，与前端旧版视觉口径一致）。 */
    public List<TrendPoint> trend7d() {
        int[] totals = {9, 13, 11, 16, 14, 12, 17};
        int[] high = {2, 3, 2, 4, 3, 2, 4};
        String[] labels = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};
        java.util.List<TrendPoint> points = new java.util.ArrayList<>();
        for (int i = 0; i < totals.length; i++) {
            points.add(new TrendPoint(labels[i], totals[i], high[i], true));
        }
        return points;
    }
}
