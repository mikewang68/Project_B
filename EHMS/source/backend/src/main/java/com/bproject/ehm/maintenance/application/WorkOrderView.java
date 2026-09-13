package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderTransition;

import java.time.Instant;
import java.util.List;

public record WorkOrderView(
        String orderNo,
        String deviceCode,
        String deviceName,
        String title,
        String priority,
        String status,
        String assignee,
        String source,
        String description,
        String plannedWindow,
        Instant createdAt,
        Instant updatedAt,
        List<WorkOrderTransition> history,
        Long version
) {
    static WorkOrderView from(WorkOrder order) {
        return new WorkOrderView(order.orderNo(), order.deviceCode(), order.deviceName(), order.title(),
                order.priority(), order.status().label(), order.assignee(), order.source(), order.description(),
                order.plannedWindow(), order.createdAt(), order.updatedAt(), order.history(), order.version());
    }
}
