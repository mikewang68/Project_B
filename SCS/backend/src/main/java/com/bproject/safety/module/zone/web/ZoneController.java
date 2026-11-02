package com.bproject.safety.module.zone.web;

import com.bproject.safety.module.zone.model.ZoneSnapshot;
import com.bproject.safety.module.zone.service.ZoneStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 装卸区状态查询（首页态势卡片）。 */
@RestController
@RequestMapping("/api/v1/zones")
@Tag(name = "装卸区状态", description = "A 区限位开关人员作业状态 / B 区位置传感器车辆进出状态")
public class ZoneController {

    private final ZoneStateService service;

    public ZoneController(ZoneStateService service) {
        this.service = service;
    }

    @GetMapping
    @Operation(summary = "装卸区状态列表（未收到上报的区域 online=false、字段为 null）")
    public Map<String, List<ZoneSnapshot>> list() {
        return Map.of("list", service.list());
    }
}
