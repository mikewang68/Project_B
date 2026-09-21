package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.model.PendingEventStatuses;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 离线边缘事件队列存储抽象（InMemory，不接真实 RocketMQ）。
 *
 * <p>Phase B：契约 persistence-neutral——不暴露 clear/findMutable；补传去重权威来自本仓储
 * （{@link #findByIdempotencyKey(String)}），补传顺序由 {@link #findPendingForReplay(String)}
 * 与 {@link #queueOrder()} 表达，Service 不再依赖内存 HashSet 或 List 排序。</p>
 */
public interface EdgeEventQueueRepository {

    /** 保存 / 更新事件（整体快照，copy-on-write）。 */
    EdgePendingEvent save(EdgePendingEvent event);

    Optional<EdgePendingEvent> findByEventId(String eventId);

    /** 按幂等键查找（补传去重权威；未来对应 openGauss UNIQUE(idempotency_key)）。 */
    Optional<EdgePendingEvent> findByIdempotencyKey(String idempotencyKey);

    /** 按节点 / 状态过滤（均为可选，null 表示不过滤）。返回稳定排序的副本。 */
    List<EdgePendingEvent> query(String nodeId, String status);

    /**
     * 某节点待补传事件（PENDING / FAILED，不含 SYNCING），按补传顺序稳定排序。
     * 未来对应 SQL：WHERE edge_node_id=? AND status IN ('PENDING','FAILED')
     * ORDER BY edge_occurred_at NULLS LAST, event_id。
     */
    List<EdgePendingEvent> findPendingForReplay(String nodeId);

    /** 全部事件（副本，稳定排序）。 */
    List<EdgePendingEvent> findAll();

    /** 某节点仍占用队列（PENDING/SYNCING/FAILED）的事件数。 */
    int pendingCount(String nodeId);

    long count();

    /**
     * 补传顺序（任务书第三十七节）：按 edgeOccurredAt 升序（null 最后），相同时间按 eventId 升序。
     */
    static Comparator<EdgePendingEvent> queueOrder() {
        return Comparator
                .comparing((EdgePendingEvent e) -> e.edgeOccurredAt,
                        Comparator.nullsLast(Comparator.naturalOrder()))
                .thenComparing(e -> e.eventId);
    }

    /** PENDING / FAILED 判定（SYNCING 表示正在当前事务中处理，不再被新的 replay 捞取）。 */
    static boolean replayable(String status) {
        return PendingEventStatuses.PENDING.equals(status) || PendingEventStatuses.FAILED.equals(status);
    }
}
