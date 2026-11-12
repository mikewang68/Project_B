package com.bproject.safety.module.zone.web;

import com.bproject.safety.module.zone.model.ZonePresence;
import com.bproject.safety.module.zone.model.ZoneSnapshot;
import com.bproject.safety.module.zone.service.ZonePresenceService;
import com.bproject.safety.module.zone.service.ZoneStateService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 装卸区状态查询（首页态势卡片）。 */
@RestController
@RequestMapping("/api/v1/zones")
@Tag(name = "装卸区状态", description = "区域实时人员在场（固定区域+已生效临时围栏）/ 设备上报状态")
public class ZoneController {

    /** list=设备上报状态（未收到上报 online=false）；presence=固定区域与已生效临时围栏的实时在场人数。 */
    public record ZonesResponse(List<ZoneSnapshot> list, List<ZonePresence> presence) {
    }

    private final ZoneStateService service;
    private final ZonePresenceService presenceService;

    public ZoneController(ZoneStateService service, ZonePresenceService presenceService) {
        this.service = service;
        this.presenceService = presenceService;
    }

    @GetMapping
    @Operation(summary = "装卸区状态：设备上报状态 + 固定区域/已生效临时围栏的实时在场人数")
    public ZonesResponse list() {
        return new ZonesResponse(service.list(), presenceService.list());
    }
}
