package com.bproject.safety.module.projection.screen;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 安全大屏只读投影 DTO（聚合自 AlertRepository，不建独立数据表 / Repository）。
 * 在岗人数、设备台账等暂无后端来源的字段为 Demo 聚合常量，使用 demo 标记显式说明。
 */
public final class ScreenDtos {

    private ScreenDtos() {
    }

    public record DeviceHealthItem(String name, int online, int total, boolean demo) {
    }

    public record RiskTypeCount(String type, int count) {
    }

    public record TrendPoint(String label, int total, int highRisk, boolean demo) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScreenOverview(
            int onDuty,
            int deviceOnline,
            int deviceTotal,
            int todayAlerts,
            int processingAlerts,
            int urgentAlerts,
            int severeAlerts,
            boolean onDutyDemo,
            boolean deviceDemo,
            List<DeviceHealthItem> deviceHealth,
            List<RiskTypeCount> riskTypeDistribution,
            List<TrendPoint> riskTrend) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScreenFeedItem(
            String id,
            String title,
            String level,
            String area,
            String objectName,
            String status,
            String eventType,
            String source,
            String occurredAt,
            String summary) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ScreenCritical(
            String id,
            String title,
            String level,
            String area,
            String objectName,
            String status,
            String assignee,
            Integer durationSec,
            String occurredAt,
            String summary) {
    }
}
