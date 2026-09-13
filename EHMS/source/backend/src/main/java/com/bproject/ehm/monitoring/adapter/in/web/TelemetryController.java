package com.bproject.ehm.monitoring.adapter.in.web;

import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1/telemetry")
public class TelemetryController {
    private final TelemetrySeriesPort telemetry;

    public TelemetryController(TelemetrySeriesPort telemetry) {
        this.telemetry = telemetry;
    }

    @GetMapping("/points/{pointCode}/samples")
    public List<TelemetrySeriesPort.TelemetrySample> history(
            @PathVariable String pointCode,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "500") int limit
    ) {
        return telemetry.history(pointCode, from, to, limit);
    }
}
