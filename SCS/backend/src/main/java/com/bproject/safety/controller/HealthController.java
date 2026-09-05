package com.bproject.safety.controller;

import com.bproject.safety.infrastructure.health.ReadinessAggregator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 进程级健康端点（供 Easegress / systemd / 负载均衡探测，前端不依赖 Actuator）。
 * <ul>
 *   <li>/health/live：进程存活，恒定 UP；</li>
 *   <li>/health/ready：汇总 application/openGauss/Kvrocks/RocketMQ/openGemini。</li>
 * </ul>
 */
@RestController
@Tag(name = "Health", description = "存活与就绪检查")
public class HealthController {
    private final ReadinessAggregator readinessAggregator;

    public HealthController(ReadinessAggregator readinessAggregator) {
        this.readinessAggregator = readinessAggregator;
    }

    @Operation(summary = "进程存活检查", description = "返回 {\"status\":\"UP\"}")
    @GetMapping("/health/live")
    public Map<String, String> live() {
        return Map.of("status", "UP");
    }

    @Operation(summary = "就绪检查", description = "汇总 openGauss/Kvrocks/RocketMQ/openGemini 连接状态")
    @GetMapping("/health/ready")
    public ResponseEntity<ReadinessAggregator.ReadinessReport> ready() {
        ReadinessAggregator.ReadinessReport report = readinessAggregator.aggregate();
        boolean up = "UP".equals(report.status());
        return ResponseEntity.status(up ? HttpStatus.OK : HttpStatus.SERVICE_UNAVAILABLE).body(report);
    }
}
