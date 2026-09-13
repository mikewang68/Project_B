package com.bproject.ehm.analytics.adapter.in.web;

import com.bproject.ehm.analytics.application.DashboardApplicationService;
import com.bproject.ehm.analytics.application.DashboardSummary;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ehm/v1/dashboard")
public class DashboardController {
    private final DashboardApplicationService dashboard;

    public DashboardController(DashboardApplicationService dashboard) {
        this.dashboard = dashboard;
    }

    @GetMapping("/summary")
    public DashboardSummary summary() {
        return dashboard.summary();
    }
}
