package com.bproject.safety.module.analytics.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/** 统计分析聚合 DTO（对齐前端 frontend/src/types/analytics.ts）。 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public final class AnalyticsDtos {

    private AnalyticsDtos() {
    }

    public record Kpi(String key, String label, String value, double deltaPct, String direction,
                      boolean goodWhenDown, String hint, String variant, Long rawSec) {
    }

    public record TrendPoint(String date, long total, long high, boolean demoBaseline) {
    }

    public record RiskTypeCount(String type, long count, long high) {
    }

    public record AreaRisk(String area, long total, long high, long score) {
    }

    public record TeamEfficiency(String team, Long confirmSec, Long arriveSec, Long closeSec,
                                 long eventCount, double closeRate) {
    }

    public record TypeSplit(String type, long count) {
    }

    public record RecentEvent(String time, String type, String level) {
    }

    public record HotDevice(String deviceId, String name, long count, String primaryRisk, long primaryCount,
                            String trend, String recentRisk, List<TypeSplit> typeSplit,
                            List<RecentEvent> recentEvents) {
    }

    public record RepeatPerson(String personId, String name, String team, long count, String mainType,
                               List<String> recent) {
    }

    public record LevelCount(String level, long count) {
    }

    public record EventItem(String id, String time, String type, String area, String target, String level,
                            String status, Integer durationSec, String team, String deviceId) {
    }

    public record Dataset(List<Kpi> kpi, List<TrendPoint> trend, List<RiskTypeCount> riskTypes,
                          List<AreaRisk> areas, List<TeamEfficiency> teams, List<HotDevice> devices,
                          List<RepeatPerson> persons, List<LevelCount> levels, boolean trendDemoBaseline) {
    }

    public record EventPage(int page, int pageSize, long total, List<EventItem> list) {
    }
}
