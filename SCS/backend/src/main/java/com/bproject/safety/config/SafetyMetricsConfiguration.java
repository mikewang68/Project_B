package com.bproject.safety.config;

import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.ops.repository.EdgeEventQueueRepository;
import com.bproject.safety.module.projection.shared.SafetyProjectionService;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Configuration;

/**
 * D-2 业务指标绑定：把 SCS 核心安全态势注册为 Micrometer gauge，
 * 经 /actuator/prometheus 输出，供 Prometheus / Nightingale(categraf) 抓取。
 *
 * <p>指标清单：
 * <ul>
 *   <li>{@code scs.alerts.total} / {@code scs.alerts.open} / {@code scs.alerts.processing}
 *       / {@code scs.alerts.closed}——告警四态态势（读取经 P0-4 下推的 GROUP BY 计数契约，
 *       server profile 下每次抓取为 3 个轻量聚合查询）；</li>
 *   <li>{@code scs.edge.pending.events}——边缘断网补传队列总深度（云边协同健康度核心信号，
 *       持续增长说明补传链路异常）。</li>
 * </ul>
 * 抓取方应设置 ≥15s 间隔，避免高频 gauge 回调放大数据库负载。</p>
 */
@Configuration
public class SafetyMetricsConfiguration {

    public SafetyMetricsConfiguration(MeterRegistry registry,
                                      SafetyProjectionService projection,
                                      EdgeEventQueueRepository edgeQueue) {
        Gauge.builder("scs.alerts.total", projection, SafetyProjectionService::allAlertCount)
                .description("SCS 告警总量")
                .register(registry);
        Gauge.builder("scs.alerts.open", projection, SafetyProjectionService::activeCount)
                .description("未关闭告警数（活动告警）")
                .tag("state", "open")
                .register(registry);
        Gauge.builder("scs.alerts.processing", projection, SafetyProjectionService::processingCount)
                .description("处置中告警数（处理中/待复核/已升级）")
                .tag("state", AlertStatuses.PROCESSING)
                .register(registry);
        Gauge.builder("scs.alerts.closed", projection, SafetyProjectionService::closedCount)
                .description("已关闭告警数")
                .tag("state", AlertStatuses.CLOSED)
                .register(registry);
        Gauge.builder("scs.edge.pending.events", edgeQueue, EdgeEventQueueRepository::count)
                .description("边缘断网补传队列总深度（持续增长=补传链路异常）")
                .register(registry);
    }
}
