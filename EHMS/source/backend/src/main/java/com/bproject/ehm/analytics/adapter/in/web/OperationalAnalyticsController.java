package com.bproject.ehm.analytics.adapter.in.web;

import com.bproject.ehm.analytics.application.OperationalAnalyticsService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/ehm/v1/analytics")
public class OperationalAnalyticsController {
    private final OperationalAnalyticsService analytics;
    public OperationalAnalyticsController(OperationalAnalyticsService analytics) {this.analytics=analytics;}
    @GetMapping("/overview")
    public OperationalAnalyticsService.AnalyticsView overview(@RequestParam(defaultValue="30") int days) {
        return analytics.overview(days);
    }
}
