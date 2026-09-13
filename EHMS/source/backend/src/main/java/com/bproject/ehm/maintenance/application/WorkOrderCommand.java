package com.bproject.ehm.maintenance.application;

public record WorkOrderCommand(
        String deviceCode,
        String title,
        String priority,
        String assignee,
        String source,
        String description,
        String plannedWindow,
        String operator
) {
}
