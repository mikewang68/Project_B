package com.bproject.safety.module.analytics.web;

import com.bproject.safety.module.analytics.dto.AnalyticsDtos.Dataset;
import com.bproject.safety.module.analytics.dto.AnalyticsDtos.EventPage;
import com.bproject.safety.module.analytics.service.AnalyticsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 统计分析接口：单聚合数据集 + 明细分页下钻 + 高频对象详情 + 风险突增演示（Backend Demo）。 */
@RestController
@RequestMapping("/api/v1/analytics")
@Tag(name = "统计分析", description = "安全风险趋势、区域 / 班组 / 设备 / 人员聚合分析（Demo）")
public class AnalyticsController {

    private final AnalyticsService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public AnalyticsController(AnalyticsService service,
                               com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping("/dataset")
    @Operation(summary = "统计聚合数据集（KPI + 趋势 + 各排行，一次返回）")
    public Dataset dataset(@RequestParam(required = false) String period,
                           @RequestParam(required = false) String from,
                           @RequestParam(required = false) String to,
                           @RequestParam(required = false) String area,
                           @RequestParam(required = false) String team,
                           @RequestParam(required = false) String type,
                           @RequestParam(required = false) String level) {
        return service.dataset(period, from, to, area, team, type, level);
    }

    @GetMapping("/events")
    @Operation(summary = "事件明细（图表下钻 + 分页）")
    public EventPage events(@RequestParam(required = false) String period,
                            @RequestParam(required = false) String from,
                            @RequestParam(required = false) String to,
                            @RequestParam(required = false) String area,
                            @RequestParam(required = false) String team,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String level,
                            @RequestParam(required = false) String deviceId,
                            @RequestParam(required = false, name = "person") String person,
                            @RequestParam(required = false, name = "personnelId") String personnelId,
                            @RequestParam(defaultValue = "1") int page,
                            @RequestParam(defaultValue = "10") int pageSize) {
        String pid = person != null ? person : personnelId;
        return service.events(period, from, to, area, team, type, level, deviceId, pid, page, pageSize);
    }

    @GetMapping("/devices/{id}")
    @Operation(summary = "高频风险设备详情")
    public Map<String, Object> deviceDetail(@PathVariable String id) {
        return service.deviceDetail(id);
    }

    @GetMapping("/persons/{id}")
    @Operation(summary = "重复风险人员详情")
    public Map<String, Object> personDetail(@PathVariable String id) {
        return service.personDetail(id);
    }

    @PostMapping("/simulate-surge")
    @Operation(summary = "切换风险突增场景（SIMULATED：向告警存储注入 / 移除演示事件）")
    public Map<String, Object> simulateSurge(@RequestBody(required = false) SurgeRequest request) {
        demoGuard.requireSimulator();
        String area = request == null ? null : request.area();
        Boolean active = request == null ? null : request.active();
        return service.simulateSurge(area, active);
    }

    public record SurgeRequest(String area, Boolean active) {
    }
}
