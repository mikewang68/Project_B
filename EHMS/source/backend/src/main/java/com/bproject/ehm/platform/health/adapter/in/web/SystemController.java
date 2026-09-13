package com.bproject.ehm.platform.health.adapter.in.web;

import com.bproject.ehm.config.UnifiedConfigService;
import com.bproject.ehm.platform.health.ports.ReadinessProbe;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class SystemController {
    private final ReadinessProbe readinessProbe;
    private final UnifiedConfigService unifiedConfig;
    private final String storageAdapter;
    private final String telemetryAdapter;

    public SystemController(ReadinessProbe readinessProbe, UnifiedConfigService unifiedConfig,
                            @Value("${ehm.persistence.adapter:MongoDB}") String storageAdapter,
                            @Value("${ehm.telemetry.adapter:disabled}") String telemetryAdapter) {
        this.readinessProbe = readinessProbe;
        this.unifiedConfig = unifiedConfig;
        this.storageAdapter = storageAdapter;
        this.telemetryAdapter = telemetryAdapter;
    }

    @GetMapping("/health/live")
    public Map<String, Object> live() {
        return Map.of("status", "UP", "service", "ehm-service", "time", Instant.now());
    }

    @GetMapping("/health/ready")
    public ResponseEntity<Map<String, Object>> ready() {
        ReadinessProbe.ProbeResult probe = readinessProbe.check();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", probe.ready() ? "UP" : "DOWN");
        result.put("component", probe.component());
        result.put("adapter", probe.status());
        if (probe.message() != null) result.put("message", probe.message());
        result.put("time", Instant.now());
        return ResponseEntity.status(probe.ready() ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(result);
    }

    @GetMapping("/api/ehm/v1/system/status")
    public Map<String, Object> status() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("service", "ehm-service");
        result.put("architecture", "modular-monolith-ports-adapters");
        result.put("status", "UP");
        result.put("storageAdapter", storageAdapter);
        result.put("telemetryAdapter", telemetryAdapter);
        result.put("config", unifiedConfig.publicSummary());
        result.put("time", Instant.now());
        return result;
    }
}
