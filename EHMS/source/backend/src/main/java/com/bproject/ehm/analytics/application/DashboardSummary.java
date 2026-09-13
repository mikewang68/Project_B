package com.bproject.ehm.analytics.application;

import java.time.Instant;

public record DashboardSummary(
        long totalDevices,
        long onlineDevices,
        double onlineRate,
        long assessableDevices,
        long healthyDevices,
        long highRiskDevices,
        long openAlarms,
        long criticalOpenAlarms,
        long activeWorkOrders,
        long pendingWorkOrders,
        double averageHealth,
        double dataQuality,
        Instant updatedAt
) {
}
