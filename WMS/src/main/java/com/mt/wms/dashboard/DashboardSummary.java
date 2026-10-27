package com.mt.wms.dashboard;

import java.time.OffsetDateTime;

public record DashboardSummary(
        long warehouseCount,
        long stockInPending,
        long stockOutPending,
        long inventoryAlertCount,
        OffsetDateTime generatedAt,
        String dataSource) {
}

