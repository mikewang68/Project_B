package com.bproject.ehm.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;

@Document("work_orders")
public record WorkOrder(
        @Id String orderNo,
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
        Instant updatedAt
) {
    public WorkOrder changeStatus(String nextStatus) {
        return new WorkOrder(orderNo, deviceCode, deviceName, title, priority, nextStatus, assignee,
                source, description, plannedWindow, createdAt, Instant.now());
    }
}
