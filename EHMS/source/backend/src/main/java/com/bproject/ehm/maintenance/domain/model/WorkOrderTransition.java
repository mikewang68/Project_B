package com.bproject.ehm.maintenance.domain.model;

import java.time.Instant;

public record WorkOrderTransition(
        WorkOrderStatus from,
        WorkOrderStatus to,
        String operator,
        String reason,
        Instant occurredAt
) {
}
