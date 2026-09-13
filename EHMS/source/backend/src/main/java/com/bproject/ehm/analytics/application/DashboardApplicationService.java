package com.bproject.ehm.analytics.application;

import com.bproject.ehm.alarm.application.AlarmMetrics;
import com.bproject.ehm.alarm.application.AlarmQueryFacade;
import com.bproject.ehm.asset.application.AssetMetrics;
import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.maintenance.application.MaintenanceMetrics;
import com.bproject.ehm.maintenance.application.WorkOrderQueryFacade;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class DashboardApplicationService {
    private final AssetQueryFacade assets;
    private final AlarmQueryFacade alarms;
    private final WorkOrderQueryFacade workOrders;
    private final Clock clock;

    @Autowired
    public DashboardApplicationService(AssetQueryFacade assets, AlarmQueryFacade alarms,
                                       WorkOrderQueryFacade workOrders) {
        this(assets, alarms, workOrders, Clock.systemUTC());
    }

    DashboardApplicationService(AssetQueryFacade assets, AlarmQueryFacade alarms,
                                WorkOrderQueryFacade workOrders, Clock clock) {
        this.assets = assets;
        this.alarms = alarms;
        this.workOrders = workOrders;
        this.clock = clock;
    }

    public DashboardSummary summary() {
        AssetMetrics asset = assets.metrics();
        AlarmMetrics alarm = alarms.metrics();
        MaintenanceMetrics maintenance = workOrders.metrics();
        return new DashboardSummary(asset.total(), asset.online(), percent(asset.online(), asset.total()),
                asset.assessable(), asset.healthy(), asset.highRisk(), alarm.open(), alarm.criticalOpen(),
                maintenance.active(), maintenance.pending(), round(asset.averageHealth()),
                round(asset.averageQuality()), clock.instant());
    }

    private double percent(long value, long total) {
        return total == 0 ? 0 : Math.round(value * 1000.0 / total) / 10.0;
    }

    private double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }
}
