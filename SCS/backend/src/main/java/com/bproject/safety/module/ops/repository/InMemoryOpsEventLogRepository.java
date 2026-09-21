package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/**
 * 进程内运维日志（append-only）：最多保留最近 300 条，按时间倒序返回。
 *
 * <p>Phase B：append 存独立副本（copy-on-write），查询返回副本；清空仅通过
 * {@link DemoClearableStore#clearDemoData()} 供 Demo / Test 使用。</p>
 */
@Repository
public class InMemoryOpsEventLogRepository implements OpsEventLogRepository, DemoClearableStore {

    private static final int MAX_KEEP = 300;
    private final CopyOnWriteArrayList<OpsEventLog> store = new CopyOnWriteArrayList<>();

    @Override
    public OpsEventLog append(OpsEventLog log) {
        OpsEventLog persisted = log.copy();
        store.add(persisted);
        while (store.size() > MAX_KEEP) {
            store.remove(0);
        }
        return persisted.copy();
    }

    @Override
    public List<OpsEventLog> recent(Integer limit, String nodeId, String level, String type,
                                    String from, String to) {
        OffsetDateTime fromAt = parse(from);
        OffsetDateTime toAt = parse(to);
        Stream<OpsEventLog> s = store.stream();
        s = s.filter(l -> nodeId == null || nodeId.isBlank() || nodeId.equals(l.nodeId))
                .filter(l -> level == null || level.isBlank() || level.equalsIgnoreCase(l.level))
                .filter(l -> type == null || type.isBlank() || type.equals(l.type))
                .filter(l -> fromAt == null || (l.ts != null && !l.ts.isBefore(fromAt)))
                .filter(l -> toAt == null || (l.ts != null && !l.ts.isAfter(toAt)));
        List<OpsEventLog> list = s.sorted(Comparator.comparing((OpsEventLog l) -> l.ts,
                        Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(l -> l.id, Comparator.reverseOrder()))
                .toList();
        int max = limit == null || limit <= 0 ? 50 : Math.min(limit, MAX_KEEP);
        return list.stream().limit(max).map(OpsEventLog::copy).toList();
    }

    private OffsetDateTime parse(String iso) {
        if (iso == null || iso.isBlank()) {
            return null;
        }
        try {
            return OffsetDateTime.parse(iso);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 清空日志（DemoClearableStore，仅 Demo 种子初始化器 / 测试调用）。 */
    @Override
    public void clearDemoData() {
        store.clear();
    }
}
