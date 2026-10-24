package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.AlertPageResult;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.Map;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

/**
 * 进程内告警存储（Backend Demo 专用）：本地无需 openGauss 即可运行全部告警接口。
 *
 * <p>使用 ConcurrentHashMap 保证可见性；写操作在 Service 层对单条告警加对象锁，
 * 保证"状态变更 + 时间线追加"的原子性。</p>
 *
 * <p>Phase B：copy-on-read / copy-on-write——find/save 均经过 {@link DemoAlert#copy()}（深拷贝
 * timeline / linkage / treatment / edgeReplay）；修改 find 返回对象但不 save 不会落库，
 * 与未来 JDBC 实现语义一致。清空仅通过 {@link DemoClearableStore} 供 Demo / Test 使用。</p>
 */
@Repository
@Profile("!server")
public class InMemoryAlertRepository implements AlertRepository, DemoClearableStore {

    private final ConcurrentHashMap<String, DemoAlert> store = new ConcurrentHashMap<>();
    private final Clock clock;

    @Autowired
    public InMemoryAlertRepository(Clock clock) {
        this.clock = clock;
    }

    /** 兼容无参构造（部分单元测试直接 new；使用系统时钟）。 */
    public InMemoryAlertRepository() {
        this(Clock.systemDefaultZone());
    }

    private static final Comparator<DemoAlert> TIME_DESC = Comparator
            .comparing((DemoAlert a) -> a.occurredAt, Comparator.nullsLast(Comparator.reverseOrder()))
            .thenComparing(a -> a.id, Comparator.nullsLast(Comparator.reverseOrder()));

    @Override
    public List<DemoAlert> findAll() {
        return store.values().stream().map(DemoAlert::copy).sorted(TIME_DESC).toList();
    }

    @Override
    public List<DemoAlert> filter(AlertQuery q) {
        return store.values().stream()
                .map(DemoAlert::copy)
                .filter(a -> matchKeyword(q.keyword(), a))
                .filter(a -> q.risk() == null || RiskLevels.normalize(q.risk()).equals(a.riskCode))
                .filter(a -> q.status() == null || AlertStatuses.normalize(q.status()).equals(a.statusCode))
                .filter(a -> q.area() == null || q.area().equals(a.area))
                .filter(a -> q.eventType() == null || q.eventType().equals(a.eventType))
                .filter(a -> q.source() == null || q.source().equals(a.source))
                // Phase A：责任人筛选同时支持 userCode（USR-*）、姓名快照（可能含角色前缀）
                .filter(a -> q.assignee() == null
                        || q.assignee().equals(a.assigneeUserCode)
                        || (a.assignee != null && a.assignee.contains(q.assignee())))
                .filter(a -> matchTimeRange(q, a))
                .sorted(TIME_DESC)
                .toList();
    }

    @Override
    public AlertPageResult page(AlertQuery q) {
        List<DemoAlert> all = filter(q);
        int from = Math.min((q.page() - 1) * q.pageSize(), all.size());
        int to = Math.min(from + q.pageSize(), all.size());
        return new AlertPageResult(q.page(), q.pageSize(), all.size(), all.subList(from, to));
    }

    @Override
    public Optional<DemoAlert> findById(String id) {
        DemoAlert a = store.get(id);
        return a == null ? Optional.empty() : Optional.of(a.copy());
    }

    @Override
    public Optional<DemoAlert> findOpenByDedupKey(String dedupKey) {
        if (dedupKey == null || dedupKey.isBlank()) {
            return Optional.empty();
        }
        return store.values().stream()
                .filter(a -> dedupKey.equals(a.dedupKey)
                        && !AlertStatuses.CLOSED.equals(a.statusCode)
                        && !AlertStatuses.CANCELLED.equals(a.statusCode))
                .findFirst()
                .map(DemoAlert::copy);
    }

    @Override
    public DemoAlert save(DemoAlert alert) {
        DemoAlert persisted = alert.copy();
        if (persisted.updatedAt == null) {
            persisted.updatedAt = OffsetDateTime.now(clock);
        }
        store.put(alert.id, persisted);
        return persisted.copy();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public java.util.Map<String, Long> countByStatus() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        store.values().forEach(a -> out.merge(a.statusCode, 1L, Long::sum));
        return out;
    }

    @Override
    public java.util.Map<String, Long> countOpenByRisk() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        store.values().stream()
                .filter(a -> !AlertStatuses.CLOSED.equals(a.statusCode))
                .forEach(a -> out.merge(a.riskCode, 1L, Long::sum));
        return out;
    }

    @Override
    public java.util.Map<String, Long> countByEventType() {
        java.util.Map<String, Long> out = new java.util.LinkedHashMap<>();
        store.values().forEach(a ->
                out.merge(a.eventType == null ? a.title : a.eventType, 1L, Long::sum));
        return out;
    }

    /** 与 JDBC summarize 语义一致：同源数据单遍计算（test/dev profile 数据量小，直算即可）。 */
    @Override
    public AlertScopeSummary summarize(AlertQuery query) {
        List<DemoAlert> scope = filter(query);
        Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
        Map<String, Long> byRisk = new java.util.LinkedHashMap<>();
        Map<String, Long> byArea = new java.util.LinkedHashMap<>();
        Map<String, Long> byAreaHigh = new java.util.LinkedHashMap<>();
        Map<String, Long> byTarget = new java.util.LinkedHashMap<>();
        Map<String, Long> byEventType = new java.util.LinkedHashMap<>();
        Map<String, Long> byEventTypeHigh = new java.util.LinkedHashMap<>();
        long severeOpen = 0;
        long urgentOpen = 0;
        for (DemoAlert a : scope) {
            byStatus.merge(nz(a.statusCode), 1L, Long::sum);
            byRisk.merge(nz(a.riskCode), 1L, Long::sum);
            boolean high = com.bproject.safety.module.alert.model.RiskLevels.isHigh(a.riskCode);
            byArea.merge(nz(a.area), 1L, Long::sum);
            if (high) {
                byAreaHigh.merge(nz(a.area), 1L, Long::sum);
                byEventTypeHigh.merge(a.eventType == null ? nz(a.title) : a.eventType, 1L, Long::sum);
            }
            byTarget.merge(nz(a.target), 1L, Long::sum);
            byEventType.merge(a.eventType == null ? nz(a.title) : a.eventType, 1L, Long::sum);
            if (com.bproject.safety.module.alert.model.RiskLevels.SEVERE.equals(a.riskCode)
                    && !AlertStatuses.CLOSED.equals(a.statusCode)) {
                severeOpen++;
            }
            if (com.bproject.safety.module.alert.model.RiskLevels.URGENT.equals(a.riskCode)
                    && !AlertStatuses.CLOSED.equals(a.statusCode)) {
                urgentOpen++;
            }
        }
        return new AlertScopeSummary(scope.size(), severeOpen, urgentOpen,
                byStatus, byRisk, byArea, byAreaHigh, byTarget, byEventType, byEventTypeHigh,
                avgSecondsTo(scope, java.util.Set.of(com.bproject.safety.module.alert.model.AlertTimelineEventTypes.CONFIRMED)),
                avgSecondsTo(scope, java.util.Set.of(com.bproject.safety.module.alert.model.AlertTimelineEventTypes.CLOSED)));
    }

    private Long avgSecondsTo(List<DemoAlert> scope, java.util.Set<String> eventTypes) {
        List<Long> values = scope.stream()
                .map(a -> secondsToFirst(a, eventTypes))
                .filter(java.util.Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        return Math.round(values.stream().mapToLong(Long::longValue).average().orElse(0));
    }

    /** 与 AnalyticsService.secondsToFirst 同语义：时间线中首个 ≥occurredAt 的目标事件耗时（秒）。 */
    private static Long secondsToFirst(DemoAlert a, java.util.Set<String> eventTypes) {
        if (a.occurredAt == null || a.timeline == null) {
            return null;
        }
        return a.timeline.stream()
                .filter(t -> t.at() != null && t.eventType() != null && eventTypes.contains(t.eventType()))
                .map(t -> t.at()).filter(t -> !t.isBefore(a.occurredAt))
                .min(java.util.Comparator.naturalOrder())
                .map(t -> java.time.Duration.between(a.occurredAt, t).getSeconds())
                .orElse(null);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }

    /** 清空存储（DemoClearableStore，仅 Demo 场景维护 / 测试调用）。 */
    @Override
    public void clearDemoData() {
        store.clear();
    }

    /**
     * 按编号删除（<b>非</b>正式契约，仅 {@code DemoAlertMaintenance} 的 Demo 突增回滚使用；
     * 真实告警原则上不物理删除）。
     */
    public boolean deleteDemoAlert(String id) {
        return store.remove(id) != null;
    }

    private boolean matchKeyword(String kw, DemoAlert a) {
        if (kw == null) {
            return true;
        }
        return contains(a.id, kw) || contains(a.title, kw) || contains(a.target, kw)
                || contains(a.ruleId, kw) || contains(a.assignee, kw);
    }

    private boolean contains(String v, String kw) {
        return v != null && v.contains(kw);
    }

    /**
     * 轻量时间范围筛选：支持 ISO 起止时间（from/to）以及前端现有的"近2小时/今日"口径。
     */
    private boolean matchTimeRange(AlertQuery q, DemoAlert a) {
        if (a.occurredAt == null) {
            return q.from() == null && q.to() == null;
        }
        if (q.from() != null && a.occurredAt.isBefore(OffsetDateTime.parse(q.from()))) {
            return false;
        }
        if (q.to() != null && a.occurredAt.isAfter(OffsetDateTime.parse(q.to()))) {
            return false;
        }
        return true;
    }
}
