package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.OpsEventLog;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.springframework.stereotype.Repository;

/** 进程内运维日志：最多保留最近 300 条，按时间倒序返回。 */
@Repository
public class InMemoryOpsEventLogRepository implements OpsEventLogRepository {

    private static final int MAX_KEEP = 300;
    private final CopyOnWriteArrayList<OpsEventLog> store = new CopyOnWriteArrayList<>();

    @Override
    public OpsEventLog append(OpsEventLog log) {
        store.add(log);
        while (store.size() > MAX_KEEP) {
            store.remove(0);
        }
        return log;
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

    @Override
    public void clear() {
        store.clear();
    }
}
