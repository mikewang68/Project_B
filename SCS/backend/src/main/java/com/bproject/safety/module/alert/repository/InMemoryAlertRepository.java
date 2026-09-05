package com.bproject.safety.module.alert.repository;

import com.bproject.safety.module.alert.model.DemoAlert;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 进程内告警存储（Backend Demo 专用）：本地无需 openGauss 即可运行全部告警接口。
 *
 * <p>使用 ConcurrentHashMap 保证可见性；写操作在 Service 层对单条告警加对象锁，
 * 保证"状态变更 + 时间线追加"的原子性。查询统一返回副本，避免外部直接改写存储。</p>
 */
@Repository
public class InMemoryAlertRepository implements AlertRepository {

    private final ConcurrentHashMap<String, DemoAlert> store = new ConcurrentHashMap<>();

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
                .filter(a -> q.risk() == null || q.risk().equals(a.risk))
                .filter(a -> q.status() == null || q.status().equals(a.status))
                .filter(a -> q.area() == null || q.area().equals(a.area))
                .filter(a -> q.eventType() == null || q.eventType().equals(a.eventType))
                .filter(a -> q.source() == null || q.source().equals(a.source))
                .filter(a -> q.assignee() == null || q.assignee().equals(a.assignee))
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
    public DemoAlert save(DemoAlert alert) {
        if (alert.updatedAt == null) {
            alert.updatedAt = OffsetDateTime.now();
        }
        store.put(alert.id, alert);
        return alert.copy();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public boolean deleteById(String id) {
        return store.remove(id) != null;
    }

    /** 清空存储（供测试在每个用例前重置 Demo 数据；服务器实现替换为 openGauss 后移除）。 */
    public void clear() {
        store.clear();
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
