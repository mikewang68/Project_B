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

        return new ScreenOverview(
                SafetyProjectionService.DEMO_ON_DUTY, SafetyProjectionService.DEMO_DEVICE_ONLINE,
                SafetyProjectionService.DEMO_DEVICE_TOTAL,
                all.size(), processing, urgent, severe,
                true, true, health, distribution, trend);
    }

    /** 最近事件流（默认 12 条），按发生时间倒序。 */
    public List<ScreenFeedItem> feed(int limit) {
        return projection.recent(limit, 50).stream().map(this::toFeedItem).toList();
    }

    /** 当前最高优先级的紧急且未关闭事件；不存在返回 null。 */
    public ScreenCritical critical() {
        return projection.allAlerts().stream()
                .filter(a -> "紧急".equals(a.risk) && !AlertStatuses.CLOSED.equals(a.status))
                .max(Comparator.comparing((DemoAlert a) -> a.occurredAt, Comparator.nullsFirst(Comparator.naturalOrder()))
                        .thenComparing(a -> a.id))
                .map(a -> new ScreenCritical(a.id, a.title, a.risk, a.area, a.target, a.status,
                        a.assignee, a.durationSec,
                        a.occurredAt == null ? null : ISO.format(a.occurredAt), projection.summaryOf(a)))
                .orElse(null);
    }

    private ScreenFeedItem toFeedItem(DemoAlert a) {
        return new ScreenFeedItem(a.id, a.title, a.risk, a.area, a.target, a.status,
                a.eventType, a.source, a.occurredAt == null ? null : ISO.format(a.occurredAt),
                projection.summaryOf(a));
    }
}
