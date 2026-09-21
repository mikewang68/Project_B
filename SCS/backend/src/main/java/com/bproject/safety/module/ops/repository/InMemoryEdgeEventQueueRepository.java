package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import com.bproject.safety.support.demo.DemoClearableStore;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Repository;

/**
 * 进程内离线事件队列：按 eventId 存储，查询按 edgeOccurredAt、eventId 稳定排序。
 *
 * <p>Phase B：copy-on-read / copy-on-write（{@link EdgePendingEvent#copy()} 深拷贝
 * payloadSummary / localLinkage）；不提供 findMutable，去重权威来自仓储查询而非 Service 的 HashSet。</p>
 */
@Repository
public class InMemoryEdgeEventQueueRepository implements EdgeEventQueueRepository, DemoClearableStore {

    private final ConcurrentHashMap<String, EdgePendingEvent> store = new ConcurrentHashMap<>();

    @Override
    public EdgePendingEvent save(EdgePendingEvent event) {
        EdgePendingEvent persisted = event.copy();
        store.put(event.eventId, persisted);
        return persisted.copy();
    }

    @Override
    public Optional<EdgePendingEvent> findByEventId(String eventId) {
        EdgePendingEvent e = store.get(eventId);
        return e == null ? Optional.empty() : Optional.of(e.copy());
    }

    @Override
    public Optional<EdgePendingEvent> findByIdempotencyKey(String idempotencyKey) {
        if (idempotencyKey == null) {
            return Optional.empty();
        }
        return store.values().stream()
                .filter(e -> idempotencyKey.equals(e.idempotencyKey))
                .findFirst()
                .map(EdgePendingEvent::copy);
    }

    @Override
    public List<EdgePendingEvent> query(String nodeId, String status) {
        return store.values().stream()
                .filter(e -> nodeId == null || nodeId.isBlank() || nodeId.equals(e.edgeNodeId))
                .filter(e -> status == null || status.isBlank() || status.equals(e.status))
                .map(EdgePendingEvent::copy)
                .sorted(EdgeEventQueueRepository.queueOrder())
                .toList();
    }

    @Override
    public List<EdgePendingEvent> findPendingForReplay(String nodeId) {
        return store.values().stream()
                .filter(e -> nodeId == null || nodeId.isBlank() || nodeId.equals(e.edgeNodeId))
                .filter(e -> EdgeEventQueueRepository.replayable(e.status))
                .map(EdgePendingEvent::copy)
                .sorted(EdgeEventQueueRepository.queueOrder())
                .toList();
    }

    @Override
    public List<EdgePendingEvent> findAll() {
        return store.values().stream().map(EdgePendingEvent::copy).sorted(EdgeEventQueueRepository.queueOrder()).toList();
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

    /** 清空队列（DemoClearableStore，仅 Demo 种子初始化器 / 测试调用）。 */
    @Override
    public void clearDemoData() {
        store.clear();
    }
}
