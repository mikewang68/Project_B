package com.bdemo.energy.profile;

import com.bdemo.common.AjaxResult;
import com.bdemo.config.EnergyDataScopeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/equipment-profiles")
public class EquipmentProfileController {
    private final EquipmentProfileService service;
    private final EnergyDataScopeService dataScope;

    public EquipmentProfileController(EquipmentProfileService service, EnergyDataScopeService dataScope) {
        this.service = service;
        this.dataScope = dataScope;
    }

    // REQ-034: the card wall heat and alert counts are calculated for the selected window.
    @GetMapping
    public AjaxResult list(@RequestParam(defaultValue = "ALL") String zone,
                           @RequestParam(defaultValue = "ELEC") String energyType,
                           @RequestParam(required = false) String periodStart,
                           @RequestParam(required = false) String periodEnd,
                           Authentication authentication) {
        return AjaxResult.success(service.list(dataScope.zone(authentication, zone), energyType, periodStart, periodEnd))
                .add("msg", "equipment profiles");
    }

    // REQ-033~035: equipment state, work-order coverage and inefficiency evidence share one window.
    @GetMapping("/{equipmentCode}")
    public AjaxResult detail(@PathVariable String equipmentCode,
                             @RequestParam(defaultValue = "ELEC") String energyType,
                             @RequestParam(required = false) String periodStart,
                             @RequestParam(required = false) String periodEnd,
                             @RequestParam(required = false) Long eventId,
                             Authentication authentication) {
        dataScope.checkEquipment(authentication, equipmentCode);
        return AjaxResult.success(service.detail(equipmentCode, energyType, periodStart, periodEnd, eventId))
                .add("msg", "equipment profile");
    }
}
