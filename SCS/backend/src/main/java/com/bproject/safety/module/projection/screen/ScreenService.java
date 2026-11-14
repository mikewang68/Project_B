package com.bproject.safety.module.projection.screen;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.projection.screen.ScreenDtos.DeviceHealthItem;
import com.bproject.safety.module.projection.screen.ScreenDtos.RiskTypeCount;
import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenCritical;
import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenFeedItem;
import com.bproject.safety.module.projection.screen.ScreenDtos.ScreenOverview;
import com.bproject.safety.module.projection.screen.ScreenDtos.TrendPoint;
import com.bproject.safety.module.projection.shared.SafetyProjectionService;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * 安全大屏聚合服务：所有告警口径实时来自共享聚合 {@link SafetyProjectionService}（Alert 唯一权威事件源），
 * 不维护独立 ScreenAlert 数据；人员/设备台账为显式标注的 Demo 聚合。
 */
@Service
public class ScreenService {

    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

    private final SafetyProjectionService projection;

    public ScreenService(SafetyProjectionService projection) {
        this.projection = projection;
    }

    public ScreenOverview overview() {
        List<DemoAlert> all = projection.allAlerts();
        int processing = (int) projection.processingCount();
        int urgent = (int) projection.countByRiskOpen("紧急");
        int severe = (int) projection.countByRiskOpen("严重");

        List<RiskTypeCount> distribution = projection.riskDistribution().stream()
                .map(c -> new RiskTypeCount(c.type(), c.count()))
                .toList();
        List<DeviceHealthItem> health = projection.deviceHealth().stream()
                .map(h -> new DeviceHealthItem(h.name(), h.online(), h.total(), h.demo()))
                .toList();
        List<TrendPoint> trend = projection.trend24h().stream()
                .map(t -> new TrendPoint(t.label(), t.total(), t.highRisk(), t.demo()))
                .toList();

        // 复查②：在岗/设备台账库内聚合（demo 标志如实）
        long onDuty = projection.onDutyCount();
        boolean onDutyDemo = projection.onDutyIsDemo();

        // 今日口径：occurredAt 落在今天（服务时区）的事件；数据集不足一天时如实标注
        var todayStart = java.time.LocalDate.now().atStartOfDay(java.time.ZoneId.systemDefault()).toOffsetDateTime();
        List<DemoAlert> today = all.stream()
                .filter(a -> a.occurredAt != null && !a.occurredAt.isBefore(todayStart))
                .toList();
        boolean todayDemo = today.size() < 5; // 演示数据集稀疏时按 Demo 口径展示

        // 闭环率：CLOSED / 非取消告警；无告警数据为 null（前端回退 Demo 文案）
        long closed = all.stream().filter(a -> AlertStatuses.CLOSED.equals(a.statusCode)).count();
        long total = all.stream().filter(a -> !AlertStatuses.CANCELLED.equals(a.statusCode)).count();
        Double closedRate = total == 0 ? null : Math.round(closed * 1000.0 / total) / 10.0;

        return new ScreenOverview(
                (int) onDuty,
                health.stream().mapToInt(DeviceHealthItem::online).sum(),
                health.stream().mapToInt(DeviceHealthItem::total).sum(),
                today.size(), todayDemo,
                processing, urgent, severe,
                closedRate,
                onDutyDemo,
                health.stream().anyMatch(DeviceHealthItem::demo),
                health, distribution, trend);
    }

    /** 最近事件流（默认 12 条），按发生时间倒序。 */
    public List<ScreenFeedItem> feed(int limit) {
        return projection.recent(limit, 50).stream().map(this::toFeedItem).toList();
    }

    /** 当前最高优先级的紧急且未关闭事件；不存在返回 null。 */
    public ScreenCritical critical() {
        return projection.allAlerts().stream()
                .filter(a -> com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode)
                        && !AlertStatuses.CLOSED.equals(a.statusCode))
                .max(Comparator.comparing((DemoAlert a) -> a.occurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(a -> a.id))
                .map(a -> new ScreenCritical(a.id, a.title, a.getRisk(), a.area, a.target, a.getStatus(),
                        a.assignee, a.durationSec,
                        a.occurredAt == null ? null : ISO.format(a.occurredAt), projection.summaryOf(a)))
                .orElse(null);
    }

    private ScreenFeedItem toFeedItem(DemoAlert a) {
        return new ScreenFeedItem(a.id, a.title, a.getRisk(), a.area, a.target, a.getStatus(),
                a.eventType, a.source, a.occurredAt == null ? null : ISO.format(a.occurredAt),
                projection.summaryOf(a));
    }
}
