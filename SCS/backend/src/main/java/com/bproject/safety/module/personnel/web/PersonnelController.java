package com.bproject.safety.module.personnel.web;

import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.model.TrackPoint;
import com.bproject.safety.module.personnel.service.PersonnelService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 人员定位接口：列表 / 详情 / 轨迹 / 实时位置 / 手环提醒 / 相关告警 / 异常模拟。
 * 成功响应直接返回业务体（不包装 code/data）。
 */
@RestController
@RequestMapping("/api/v1/personnel")
@Tag(name = "人员定位", description = "人员台账、轨迹、实时位置与越界风险（风险进入 Alert 主链）")
public class PersonnelController {

    private final PersonnelService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public PersonnelController(PersonnelService service,
                               com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping
    @Operation(summary = "人员列表（含筛选与统计）")
    public PersonnelService.PersonnelList list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) String team,
                                               @RequestParam(required = false) String area,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String bracelet,
                                               @RequestParam(required = false) Boolean onlyAbnormal) {
        return service.list(keyword, team, area, state, bracelet, onlyAbnormal);
    }

    @GetMapping("/live")
    @Operation(summary = "在线人员实时位置（批量，轻量）")
    public Map<String, List<PersonnelService.LivePosition>> live(@RequestParam(required = false) String area) {
        return Map.of("list", service.live(area));
    }

    @GetMapping("/{id}")
    @Operation(summary = "人员详情（含未关闭告警编号摘要）")
    public DemoPersonnel detail(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/{id}/track")
    @Operation(summary = "人员历史轨迹（Demo 固定样本）")
    public Map<String, List<TrackPoint>> track(@PathVariable String id) {
        return Map.of("points", service.track(id));
    }

    @GetMapping("/{id}/alerts")
    @Operation(summary = "该人员相关告警（权威数据来自 Alert）")
    public Map<String, List<DemoAlert>> alerts(@PathVariable String id,
                                               @RequestParam(required = false) Integer limit) {
        return Map.of("list", service.alerts(id, limit));
    }

    @PostMapping("/{id}/remind")
    @Operation(summary = "发送手环提醒（Mock 下发）")
    public PersonnelService.RemindResult remind(@PathVariable String id,
                                                @RequestBody(required = false) Map<String, String> body) {
        String message = body == null ? null : body.get("message");
        return service.remind(id, message);
    }

    @PostMapping("/simulate-abnormal")
    @Operation(summary = "模拟人员异常（SIMULATED：lowBattery/offline/intrusion/restore）")
    public DemoPersonnel simulate(@RequestBody(required = false) Map<String, String> body) {
        demoGuard.requireSimulator();
        String id = body == null ? null : body.getOrDefault("id", "P-ZHAO");
        String kind = body == null ? "lowBattery" : body.getOrDefault("kind", "lowBattery");
        return service.simulateAbnormal(id, kind);
    }
}
