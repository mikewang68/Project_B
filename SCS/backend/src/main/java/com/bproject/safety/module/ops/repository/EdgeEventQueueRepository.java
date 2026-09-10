package com.bproject.safety.module.ops.repository;

import com.bproject.safety.module.ops.model.EdgePendingEvent;
import java.util.List;
import java.util.Optional;

/** 离线边缘事件队列存储抽象（InMemory，不接真实 RocketMQ）。 */
public interface EdgeEventQueueRepository {

    /** 保存 / 更新事件。 */
    EdgePendingEvent save(EdgePendingEvent event);

    Optional<EdgePendingEvent> findByEventId(String eventId);

    /** 按节点 / 状态过滤（均为可选，null 表示不过滤）。返回入队顺序的副本。 */
    List<EdgePendingEvent> query(String nodeId, String status);

    /** 全部事件（副本）。 */
    List<EdgePendingEvent> findAll();

    /** 某节点仍占用队列（PENDING/SYNCING/FAILED）的事件数。 */
    int pendingCount(String nodeId);

    long count();

    void clear();
}
