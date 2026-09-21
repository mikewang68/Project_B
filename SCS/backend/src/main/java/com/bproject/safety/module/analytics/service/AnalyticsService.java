package com.bproject.safety.module.analytics.service;

import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.AlertTimelineEventTypes;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.model.TimelineEvent;
import com.bproject.safety.module.alert.demo.DemoAlertMaintenance;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.AreaRisk;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.Dataset;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.EventItem;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.EventPage;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.HotDevice;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.Kpi;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.LevelCount;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.RecentEvent;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.RepeatPerson;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.RiskTypeCount;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.TeamEfficiency;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.TrendPoint;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.TypeSplit;
import com.bproject.safety.module.projection.shared.RiskClassifier;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * 统计分析聚合服务（Projection / Query）：不维护独立业务数据，全部从 {@link AlertRepository}
 * 实时聚合；人员 / 设备基础台账尚未后端化的部分（班组映射、设备命名、历史趋势基线）显式标注 Demo。
 *
 * <p>“风险突增演示”向告警存储注入一组 demoScenario 事件并广播，因此首页 / 大屏 / 统计同步变化，
 * 恢复时按固定前缀删除，不污染种子数据。</p>
 */
@Service
public class AnalyticsService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter MD_HM = DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private static final DateTimeFormatter MD = DateTimeFormatter.ofPattern("MM/dd");
    /** 风险等级按机器 code 排序，展示标签由 RiskLevels 派生。 */
    private static final List<String> LEVEL_CODES = RiskLevels.ORDERED;
    private static final String SURGE_PREFIX = "ALM-SURGE-";
    /** 本周历史桶 DEMO 基线（仅用于无历史时序库时的曲线展示，当天桶永远使用真实计数）。 */
    private static final int[] WEEK_BASELINE = {15, 18, 14, 20, 17, 22};

    private final AlertRepository alerts;
    private final DomainLivePublisher publisher;
    private final Clock clock;
    /**
     * Phase B：风险突增演示的注入 / 回滚经 Demo 维护组件（deleteById 不属于正式仓储契约）；
     * 仅 simulateSurge 使用，Controller 层已由 DemoFeatureGuard 门控。
     */
    private final DemoAlertMaintenance demoMaintenance;

    public AnalyticsService(AlertRepository alerts, DomainLivePublisher publisher, Clock clock,
                            DemoAlertMaintenance demoMaintenance) {
        this.alerts = alerts;
        this.publisher = publisher;
        this.clock = clock;
        this.demoMaintenance = demoMaintenance;
    }

    // ---------- 聚合数据集 ----------

    public Dataset dataset(String period, String from, String to, String area, String team,
                           String type, String level) {
        List<DemoAlert> scope = filter(period, from, to, area, team, type, level, null, null);

        List<Kpi> kpi = buildKpi(scope);
        List<TrendPoint> trend = buildTrend(scope, period);
        List<RiskTypeCount> riskTypes = countMap(scope).entrySet().stream()
                .sorted(Map.Entry.<String, long[]>comparingByValue((a, b) -> Long.compare(b[0], a[0])))
                .map(e -> new RiskTypeCount(e.getKey(), e.getValue()[0], e.getValue()[1]))
                .toList();
        List<AreaRisk> areas = scope.stream().collect(Collectors.groupingBy(a -> nz(a.area), LinkedHashMap::new,
                        Collectors.toList())).entrySet().stream()
                .map(e -> {
                    long high = e.getValue().stream().filter(RiskClassifier::isHighRisk).count();
                    long total = e.getValue().size();
                    // DEMO RISK SCORE：总量 + 2×高风险，仅用于排行排序，非正式风险模型
                    return new AreaRisk(e.getKey(), total, high, total + 2 * high);
                })
                .sorted(Comparator.comparingLong(AreaRisk::score).reversed())
                .toList();
        List<TeamEfficiency> teams = buildTeams(scope);
        List<HotDevice> devices = buildDevices(scope);
        List<RepeatPerson> persons = buildPersons(scope);
        List<LevelCount> levels = LEVEL_CODES.stream()
                .map(code -> new LevelCount(RiskLevels.label(code),
                        scope.stream().filter(a -> code.equals(a.riskCode)).count()))
                .toList();
        return new Dataset(kpi, trend, riskTypes, areas, teams, devices, persons, levels, true);
    }

    private List<Kpi> buildKpi(List<DemoAlert> scope) {
        long total = scope.size();
        long severe = scope.stream().filter(a -> RiskLevels.SEVERE.equals(a.riskCode)).count();
        long urgent = scope.stream().filter(a -> RiskLevels.URGENT.equals(a.riskCode)).count();
        long high = severe + urgent;
        Long avgResp = average(scope, AnalyticsService::confirmSeconds);
        Long avgClose = average(scope, AnalyticsService::closeSeconds);
        Map<String, List<DemoAlert>> byTarget = scope.stream()
                .collect(Collectors.groupingBy(a -> nz(a.target), LinkedHashMap::new, Collectors.toList()));
        long repeated = byTarget.values().stream().filter(l -> l.size() >= 2).mapToLong(List::size).sum();
        List<Kpi> kpi = new ArrayList<>();
        kpi.add(new Kpi("total", "事件总数", String.valueOf(total), 0, "flat", true,
                "Backend 实时聚合（无环比基线）", "hero", null));
        kpi.add(new Kpi("high", "高风险事件", String.valueOf(high), 0, "flat", true,
                "严重 " + severe + " · 紧急 " + urgent, "default", null));
        kpi.add(new Kpi("response", "平均响应时间", fmtDuration(avgResp), 0, "flat", true,
                "发生到确认的平均耗时", "time", avgResp));
        kpi.add(new Kpi("close", "平均关闭时间", fmtDuration(avgClose), 0, "flat", true,
                "发生到关闭的平均耗时", "time", avgClose));
        kpi.add(new Kpi("repeat", "重复风险事件", String.valueOf(repeated), 0, "flat", true,
                "同一对象重复触发的事件数", "default", null));
        return kpi;
    }

    private List<TrendPoint> buildTrend(List<DemoAlert> scope, String period) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if ("今日".equals(period)) {
            List<TrendPoint> points = new ArrayList<>();
            for (int h = 0; h < 24; h++) {
                final int hour = h;
                long c = scope.stream().filter(a -> a.occurredAt != null
                        && a.occurredAt.atZoneSameInstant(ZONE).getHour() == hour).count();
                long hi = scope.stream().filter(a -> a.occurredAt != null
                        && a.occurredAt.atZoneSameInstant(ZONE).getHour() == hour
                        && RiskClassifier.isHighRisk(a)).count();
                points.add(new TrendPoint(String.format("%02d:00", h), c, hi, false));
            }
            return points;
        }
        int days = "本月".equals(period) ? 30 : 7;
        List<TrendPoint> points = new ArrayList<>();
        for (int i = days - 1; i >= 0; i--) {
            LocalDate day = now.atZoneSameInstant(ZONE).toLocalDate().minusDays(i);
            long c = scope.stream().filter(a -> sameDay(a, day)).count();
            long hi = scope.stream().filter(a -> sameDay(a, day) && RiskClassifier.isHighRisk(a)).count();
            boolean today = i == 0;
            if (!today) {
                // 历史桶叠加 DEMO 基线（本周固定数组；本月按确定性序列），当天桶只用真实数据
                long base = "本月".equals(period) ? ((i * 7) % 12 + 8)
                        : WEEK_BASELINE[(days - 1 - i) % WEEK_BASELINE.length];
                long baseHigh = base / 7;
                points.add(new TrendPoint(day.format(MD), c + base, hi + baseHigh, true));
            } else {
                points.add(new TrendPoint(day.format(MD), c, hi, false));
            }
        }
        return points;
    }

    private List<TeamEfficiency> buildTeams(List<DemoAlert> scope) {
        Map<String, List<DemoAlert>> byTeam = scope.stream()
                .collect(Collectors.groupingBy(RiskClassifier::teamOf, LinkedHashMap::new, Collectors.toList()));
        List<TeamEfficiency> out = new ArrayList<>();
        byTeam.forEach((team, list) -> {
            Long confirm = average(list, AnalyticsService::confirmSeconds);
            Long arrive = average(list, AnalyticsService::arriveSeconds);
            Long close = average(list, AnalyticsService::closeSeconds);
            long closed = list.stream().filter(a -> AlertStatuses.CLOSED.equals(a.statusCode)).count();
            double rate = list.isEmpty() ? 0 : Math.round(closed * 1000.0 / list.size()) / 10.0;
            out.add(new TeamEfficiency(team, confirm, arrive, close, list.size(), rate));
        });
        out.sort(Comparator.comparingLong(TeamEfficiency::eventCount).reversed());
        return out;
    }

    private List<HotDevice> buildDevices(List<DemoAlert> scope) {
        Map<String, List<DemoAlert>> byDevice = new LinkedHashMap<>();
        for (DemoAlert a : scope) {
            String id = RiskClassifier.deviceIdOf(a.target);
            if (id == null) {
                continue;
            }
            byDevice.computeIfAbsent(id, k -> new ArrayList<>()).add(a);
        }
        List<HotDevice> out = new ArrayList<>();
        byDevice.forEach((id, list) -> {
            Map<String, Long> split = list.stream().collect(Collectors.groupingBy(
                    RiskClassifier::analyticsType, LinkedHashMap::new, Collectors.counting()));
            Map.Entry<String, Long> primary = split.entrySet().stream()
                    .max(Map.Entry.comparingByValue()).orElse(null);
            List<DemoAlert> sorted = list.stream()
                    .sorted(Comparator.comparing((DemoAlert a) -> a.occurredAt,
                            Comparator.nullsLast(Comparator.reverseOrder()))).toList();
            List<RecentEvent> recent = sorted.stream().limit(5)
                    .map(a -> new RecentEvent(fmtMdHm(a), RiskClassifier.analyticsType(a), a.getRisk())).toList();
            DemoAlert last = sorted.isEmpty() ? null : sorted.get(0);
            List<TypeSplit> typeSplit = split.entrySet().stream()
                    .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                    .map(e -> new TypeSplit(e.getKey(), e.getValue())).toList();
            out.add(new HotDevice(id, RiskClassifier.deviceNameOf(id), list.size(),
                    primary == null ? "其他" : primary.getKey(), primary == null ? 0 : primary.getValue(),
                    "flat", last == null ? "" : fmtMdHm(last) + " " + RiskClassifier.analyticsType(last),
                    typeSplit, recent));
        });
        out.sort(Comparator.comparingLong(HotDevice::count).reversed());
        return out.size() <= 6 ? out : out.subList(0, 6);
    }

    private List<RepeatPerson> buildPersons(List<DemoAlert> scope) {
        Map<String, List<DemoAlert>> byPerson = new LinkedHashMap<>();
        for (DemoAlert a : scope) {
            String pid = RiskClassifier.personIdOf(a.target);
            if (pid == null) {
                continue;
            }
            byPerson.computeIfAbsent(pid, k -> new ArrayList<>()).add(a);
        }
        List<RepeatPerson> out = new ArrayList<>();
        byPerson.forEach((pid, list) -> {
            Map<String, Long> byType = list.stream().collect(Collectors.groupingBy(
                    RiskClassifier::analyticsType, Collectors.counting()));
            String mainType = byType.entrySet().stream().max(Map.Entry.comparingByValue())
                    .map(Map.Entry::getKey).orElse("其他");
            String name = RiskClassifier.personNameOf(list.get(0).target, pid);
            List<String> recent = list.stream()
                    .sorted(Comparator.comparing((DemoAlert a) -> a.occurredAt,
                            Comparator.nullsLast(Comparator.reverseOrder())))
                    .limit(3).map(a -> fmtMdHm(a) + " " + brief(a)).toList();
            out.add(new RepeatPerson(pid, name, RiskClassifier.teamOf(list.get(0)), list.size(),
                    mainType, recent));
        });
        out.sort(Comparator.comparingLong(RepeatPerson::count).reversed());
        return out.size() <= 6 ? out : out.subList(0, 6);
    }

    // ---------- 明细分页 ----------

    public EventPage events(String period, String from, String to, String area, String team, String type,
                            String level, String deviceId, String person, int page, int pageSize) {
        List<DemoAlert> scope = filter(period, from, to, area, team, type, level, deviceId, person);
        List<EventItem> items = scope.stream().map(this::toEventItem).toList();
        int p = Math.max(1, page);
        int size = pageSize <= 0 ? 10 : pageSize;
        int start = Math.min((p - 1) * size, items.size());
        int end = Math.min(start + size, items.size());
        return new EventPage(p, size, items.size(), items.subList(start, end));
    }

    public Map<String, Object> deviceDetail(String id) {
        List<DemoAlert> scope = alerts.findAll().stream()
                .filter(a -> a.target != null && a.target.contains(id)).toList();
        HotDevice device = buildDevices(scope).stream().filter(d -> d.deviceId().equals(id)).findFirst()
                .orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("deviceId", id);
        out.put("detail", device);
        out.put("events", scope.stream().map(this::toEventItem).toList());
        return out;
    }

    public Map<String, Object> personDetail(String id) {
        List<DemoAlert> scope = alerts.findAll().stream()
                .filter(a -> RiskClassifier.personIdOf(a.target) != null
                        && id.equals(RiskClassifier.personIdOf(a.target))).toList();
        RepeatPerson person = buildPersons(scope).stream().filter(p -> p.personId().equals(id)).findFirst()
                .orElse(null);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("personId", id);
        out.put("detail", person);
        out.put("events", scope.stream().map(this::toEventItem).toList());
        return out;
    }

    // ---------- 风险突增演示（SIMULATED） ----------

    public synchronized Map<String, Object> simulateSurge(String area, Boolean active) {
        String targetArea = area == null || area.isBlank() ? "装卸区 A" : area;
        boolean turnOn = active == null || active;
        if (turnOn) {
            if (alerts.findAll().stream().noneMatch(a -> a.id != null && a.id.startsWith(SURGE_PREFIX))) {
                injectSurge(targetArea);
            }
        } else {
            // Phase B：演示数据物理删除走 Demo 维护组件，正式 AlertRepository 无 deleteById。
            demoMaintenance.deleteByIdPrefix(SURGE_PREFIX);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", turnOn);
        out.put("area", targetArea);
        out.put("dataset", dataset("本周", null, null, null, null, null, null));
        return out;
    }

    private void injectSurge(String area) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        String[][] plan = {
                {"01", "赵磊（P-1003）", "紧急", "待确认", "0"},
                {"02", "王强（P-1008）", "严重", "处理中", "3"},
                {"03", "外协人员 P-1091", "严重", "待确认", "7"},
                {"04", "赵磊（P-1003）", "严重", "已关闭", "26"},
                {"05", "李伟（P-1015）", "预警", "处理中", "30"},
                {"06", "王强（P-1008）", "严重", "待确认", "49"},
        };
        int seq = 0;
        int seqNo = 1;
        for (String[] p : plan) {
            DemoAlert a = new DemoAlert();
            a.id = SURGE_PREFIX + p[0];
            a.title = "人员进入" + area + "危险区域";
            // 演示装配：中文入参一次性转机器 code（非运行时判断）。
            a.riskCode = RiskLevels.fromLabel(p[2]);
            a.eventType = "危险区域闯入";
            a.time = now.minusHours(Long.parseLong(p[4])).toLocalTime()
                    .format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
            a.area = area;
            a.target = p[1];
            a.source = "人员安全";
            a.statusCode = AlertStatuses.fromLabel(p[3]);
            a.assignee = "安全员 王建国";
            a.ruleId = "RULE-PER-001";
            a.ruleVersion = "v3.3";
            a.durationSec = 0;
            a.linkage = List.of();
            a.occurredAt = now.minusHours(Long.parseLong(p[4]));
            a.updatedAt = now;
            a.timeline = List.of(new com.bproject.safety.module.alert.model.TimelineEvent(
                    a.time, a.occurredAt, "风险突增演示：人员进入危险区域", "done",
                    AlertTimelineEventTypes.CREATED, seqNo));
            demoMaintenance.save(a);
            seq++;
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("alertId", a.id);
            data.put("status", a.getStatus());
            data.put("statusCode", a.statusCode);
            data.put("level", a.getRisk());
            data.put("riskCode", a.riskCode);
            publisher.publish(LiveEventTypes.ALERT_NEW, data);
            seqNo++;
        }
    }

    // ---------- 过滤 / 计算辅助 ----------

    private List<DemoAlert> filter(String period, String from, String to, String area, String team,
                                  String type, String level, String deviceId, String person) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        OffsetDateTime start;
        OffsetDateTime end = now.plusMinutes(1);
        if (from != null && !from.isBlank()) {
            start = OffsetDateTime.parse(from);
        } else {
            int back = switch (nz(period)) {
                case "今日" -> 48; // DEMO 窗口：种子固定 2026-09-04，今日放宽到 48h 保证演示数据可见
                case "本月" -> 24 * 31;
                default -> 24 * 7;
            };
            start = now.minusHours(back);
        }
        if (to != null && !to.isBlank()) {
            end = OffsetDateTime.parse(to);
        }
        final OffsetDateTime fs = start;
        final OffsetDateTime fe = end;
        return alerts.findAll().stream()
                .filter(a -> a.occurredAt == null || (!a.occurredAt.isBefore(fs) && !a.occurredAt.isAfter(fe)))
                .filter(a -> area == null || area.isBlank() || "全部".equals(area) || area.equals(a.area))
                .filter(a -> level == null || level.isBlank() || "全部".equals(level)
                        || RiskLevels.normalize(level).equals(a.riskCode))
                .filter(a -> team == null || team.isBlank() || "全部".equals(team)
                        || team.equals(RiskClassifier.teamOf(a)))
                .filter(a -> type == null || type.isBlank() || "全部".equals(type)
                        || type.equals(RiskClassifier.analyticsType(a)))
                .filter(a -> deviceId == null || deviceId.isBlank()
                        || deviceId.equals(RiskClassifier.deviceIdOf(a.target)))
                .filter(a -> person == null || person.isBlank()
                        || person.equals(RiskClassifier.personIdOf(a.target)))
                .sorted(Comparator.comparing((DemoAlert a) -> a.occurredAt,
                        Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
    }

    private Map<String, long[]> countMap(List<DemoAlert> scope) {
        Map<String, long[]> map = new LinkedHashMap<>();
        for (DemoAlert a : scope) {
            String t = RiskClassifier.analyticsType(a);
            long[] arr = map.computeIfAbsent(t, k -> new long[2]);
            arr[0]++;
            if (RiskClassifier.isHighRisk(a)) {
                arr[1]++;
            }
        }
        return map;
    }

    private EventItem toEventItem(DemoAlert a) {
        Integer duration = a.durationSec > 0 ? a.durationSec : null;
        return new EventItem(a.id, a.occurredAt == null ? a.time : a.occurredAt.atZoneSameInstant(ZONE)
                .format(MD_HM), RiskClassifier.analyticsType(a), a.area, a.target, a.getRisk(), a.getStatus(),
                duration, RiskClassifier.teamOf(a), RiskClassifier.deviceIdOf(a.target));
    }

    private boolean sameDay(DemoAlert a, LocalDate day) {
        return a.occurredAt != null && a.occurredAt.atZoneSameInstant(ZONE).toLocalDate().equals(day);
    }

    private static Long confirmSeconds(DemoAlert a) {
        return secondsToFirst(a, java.util.Set.of(AlertTimelineEventTypes.CONFIRMED));
    }

    private static Long arriveSeconds(DemoAlert a) {
        // 接单 / 到场均视为现场到达链路节点
        return secondsToFirst(a, java.util.Set.of(
                AlertTimelineEventTypes.ACCEPTED, AlertTimelineEventTypes.ARRIVED));
    }

    private static Long closeSeconds(DemoAlert a) {
        return secondsToFirst(a, java.util.Set.of(AlertTimelineEventTypes.CLOSED));
    }

    /** 时长统计只依赖机器 eventType（F-12），不再用中文 text 关键字匹配。 */
    private static Long secondsToFirst(DemoAlert a, java.util.Set<String> eventTypes) {
        if (a.occurredAt == null || a.timeline == null) {
            return null;
        }
        return a.timeline.stream()
                .filter(t -> t.at() != null && t.eventType() != null && eventTypes.contains(t.eventType()))
                .map(TimelineEvent::at).filter(t -> !t.isBefore(a.occurredAt))
                .min(Comparator.naturalOrder())
                .map(t -> Duration.between(a.occurredAt, t).getSeconds())
                .orElse(null);
    }

    private interface SecFunction {
        Long apply(DemoAlert a);
    }

    private Long average(List<DemoAlert> list, SecFunction fn) {
        List<Long> values = list.stream().map(fn::apply).filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    private static String fmtDuration(Long sec) {
        if (sec == null) {
            return "--";
        }
        long m = sec / 60;
        long s = sec % 60;
        if (m < 60) {
            return m + "m " + s + "s";
        }
        return (m / 60) + "h " + (m % 60) + "m";
    }

    private String fmtMdHm(DemoAlert a) {
        return a.occurredAt == null ? nz(a.time) : a.occurredAt.atZoneSameInstant(ZONE).format(MD_HM);
    }

    private String brief(DemoAlert a) {
        String t = RiskClassifier.analyticsType(a);
        return t + " · " + nz(a.area);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
