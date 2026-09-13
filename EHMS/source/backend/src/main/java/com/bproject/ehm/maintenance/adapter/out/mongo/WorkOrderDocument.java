package com.bproject.ehm.maintenance.adapter.out.mongo;

import com.bproject.ehm.maintenance.domain.model.WorkOrder;
import com.bproject.ehm.maintenance.domain.model.WorkOrderStatus;
import com.bproject.ehm.maintenance.domain.model.WorkOrderTransition;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.List;

@Document("work_orders_v2")
public class WorkOrderDocument {
    @Id private final String orderNo;
    private final String deviceCode;
    private final String deviceName;
    private final String title;
    private final String priority;
    private final String status;
    private final String assignee;
    private final String source;
    private final String description;
    private final String plannedWindow;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final List<WorkOrderTransition> history;
    @Version private final Long version;

    public WorkOrderDocument(String orderNo, String deviceCode, String deviceName, String title, String priority,
                             String status, String assignee, String source, String description, String plannedWindow,
                             Instant createdAt, Instant updatedAt, List<WorkOrderTransition> history, Long version) {
        this.orderNo = orderNo;
        this.deviceCode = deviceCode;
        this.deviceName = deviceName;
        this.title = title;
        this.priority = priority;
        this.status = status;
        this.assignee = assignee;
        this.source = source;
        this.description = description;
        this.plannedWindow = plannedWindow;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.history = history;
        this.version = version;
    }

    static WorkOrderDocument fromDomain(WorkOrder order) {
        return new WorkOrderDocument(order.orderNo(), order.deviceCode(), order.deviceName(), order.title(),
                order.priority(), order.status().name(), order.assignee(), order.source(), order.description(),
                order.plannedWindow(), order.createdAt(), order.updatedAt(), order.history(), order.version());
    }

    WorkOrder toDomain() {
        return new WorkOrder(orderNo, deviceCode, deviceName, title, priority, WorkOrderStatus.from(status),
                assignee, source, description, plannedWindow, createdAt, updatedAt, history, version);
    }
}
