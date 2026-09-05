package com.bproject.safety.module.projection.overview;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * 安全态势首页只读投影 DTO（聚合自 Alert / AiEvent 权威源，不建独立业务库）。
 * 人员/设备/围栏基础坐标暂无后端来源，为 BASE DEMO MAP DATA，并以 demo 标记显式说明；
 * 风险状态（risk/state）为 LIVE RISK OVERLAY，由未关闭高风险 Alert 实时覆盖。
 */
public final class OverviewDtos {

    private OverviewDtos() {
    }

    public record OverviewSummary(
            int onDuty,
            int deviceOnline,
            int deviceTotal,
            long activeAlerts,
            long processingAlerts,
            long urgentAlerts,
            long severeAlerts,
            long pendingAi,
            int riskyDevices,
            boolean onDutyDemo,
            boolean deviceDemo,
            boolean riskyDevicesDemo) {
    }

    public record MapPoint(double x, double y) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapPerson(String id, String name, String team, String status, int battery,
                            String area, String risk, String state, double x, double y, boolean liveOverlay) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record MapEquipment(String id, String name, String type, String state, double x, double y,
                               boolean liveOverlay) {
    }

    public record MapFence(String id, String name, String tone, String area, List<MapPoint> polygon) {
    }

    /** baseDemo=true 表示人员/设备/围栏坐标为 Demo 底图；liveOverlay=true 表示风险覆盖来自真实 Alert。 */
    public record OverviewMap(boolean baseDemo, boolean liveOverlay,
                              List<MapPerson> people, List<MapEquipment> equipment, List<MapFence> fences) {
    }

    public record FeedTimeline(String time, String title, String detail) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FeedItem(String id, String title, String level, String summary, String time,
                           String area, String objectName, String status, List<FeedTimeline> timeline) {
    }

    public record OverviewFeed(List<FeedItem> list) {
    }

    public record OverviewTrend(boolean demo, List<TrendPoint> points) {
    }

    public record TrendPoint(String label, int total, int high) {
    }

    public record OverviewDistribution(List<DistributionItem> items) {
    }

    public record DistributionItem(String type, int count) {
    }
}
