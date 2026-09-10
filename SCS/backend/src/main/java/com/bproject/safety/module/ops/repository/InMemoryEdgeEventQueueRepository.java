package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/** 进程内离线事件队列：按 eventId 存储，查询按 edgeOccurredAt、eventId 稳定排序。 */
@Repository
public class InMemoryEdgeEventQueueRepository implements EdgeEventQueueRepository {

    private final ConcurrentHashMap<String, EdgePendingEvent> store = new ConcurrentHashMap<>();

    @Override
    public EdgePendingEvent save(EdgePendingEvent event) {
        store.put(event.eventId, event);
        return event.copy();
    }

    @Override
    public Optional<EdgePendingEvent> findByEventId(String eventId) {
        EdgePendingEvent e = store.get(eventId);
        return e == null ? Optional.empty() : Optional.of(e.copy());
    }

    /** 供 Service 在锁内直接修改实体。 */
    public Optional<EdgePendingEvent> findMutable(String eventId) {
        return Optional.ofNullable(store.get(eventId));
    }

    @Override
    public List<EdgePendingEvent> query(String nodeId, String status) {
        return store.values().stream()
                .filter(e -> nodeId == null || nodeId.isBlank() || nodeId.equals(e.edgeNodeId))
                .filter(e -> status == null || status.isBlank() || status.equals(e.status))
                .map(EdgePendingEvent::copy)
                .sorted(queueOrder())
                .toList();
    }

    @Override
    public List<EdgePendingEvent> findAll() {
        return store.values().stream().map(EdgePendingEvent::copy).sorted(queueOrder()).toList();
    }

    /**
     * 补传顺序（任务书第三十七节）：按 occurredAt 升序，相同时间按 eventId 升序，稳定排序。
     */
    public static Comparator<EdgePendingEvent> queueOrder() {
        return Comparator
                .comparing((EdgePendingEvent e) -> e.edgeOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> e.eventId);
    }

    @Override
    public int pendingCount(String nodeId) {
        return (int) store.values().stream()
                .filter(e -> nodeId == null || nodeId.equals(e.edgeNodeId))
                .filter(e -> PendingEventStatuses.pendingLike(e.status))
                .count();
    }

    @Override
    public long count() {
        return store.size();
    }

    @Override
    public void clear() {
        store.clear();
    }
}
