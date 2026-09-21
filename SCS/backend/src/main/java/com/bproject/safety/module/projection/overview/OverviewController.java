package com.bproject.safety.module.projection.overview;

import com.bproject.safety.module.projection.overview.OverviewDtos.FeedItem;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewDistribution;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewFeed;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewMap;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewSummary;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewTrend;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewSummary;
import com.bproject.safety.module.projection.overview.OverviewDtos.OverviewTrend;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 安全态势首页接口（只读聚合 + SIMULATED 风险演示）。
 * 告警口径来自 Alert 权威源；人员/设备/围栏坐标为 Demo 底图，风险覆盖为真实聚合。
 */
@RestController
@RequestMapping("/api/v1/overview")
@Tag(name = "安全态势首页", description = "首页指标 / 态势地图 / 实时告警流 / 趋势与分布（聚合投影）")
public class OverviewController {

    private final OverviewService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public OverviewController(OverviewService service,
                              com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping("/summary")
    @Operation(summary = "首页核心指标（告警为真实聚合，在岗/设备为 Demo 台账并标记）")
    public OverviewSummary summary() {
        return service.summary();
    }

    @GetMapping("/map")
    @Operation(summary = "首页态势地图：Demo 底图坐标 + 未关闭高风险 Alert 动态风险覆盖")
    public OverviewMap map() {
        return service.map();
    }

    @GetMapping("/alerts/feed")
    @Operation(summary = "首页实时告警流（Alert 投影）")
    public OverviewFeed feed(@RequestParam(defaultValue = "10") int limit) {
        return service.feed(limit);
    }

    @GetMapping("/risk-trend")
    @Operation(summary = "风险趋势（range=7d 默认 / 24h，当前为 Demo 历史样本并标记 demo）")
    public OverviewTrend trend(@RequestParam(defaultValue = "7d") String range) {
        return service.trend(range);
    }

    @GetMapping("/risk-distribution")
    @Operation(summary = "风险类型分布（按 Alert 实时聚合）")
    public OverviewDistribution distribution() {
        return service.distribution();
    }

    @GetMapping("/alerts/{id}")
    @Operation(summary = "首页告警详情（复用 Alert 权威详情的投影）")
    public FeedItem alertDetail(@PathVariable String id) {
        return service.alertDetail(id);
    }

    @PostMapping("/simulate-risk")
    @Operation(summary = "SIMULATED 风险演示开关：body {\"active\":true/false}")
    public Map<String, Object> simulateRisk(@RequestBody(required = false) Map<String, Object> body,
                                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        demoGuard.requireSimulator();
        boolean active = body != null && Boolean.TRUE.equals(body.get("active"));
        service.simulateRisk(active);
        return Map.of("active", active);
    }
}
