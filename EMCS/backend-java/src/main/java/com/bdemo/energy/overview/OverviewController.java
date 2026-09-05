package com.bdemo.energy.overview;

import com.bdemo.common.AjaxResult;
import com.bdemo.config.EnergyDataScopeService;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/overview")
public class OverviewController {
    private final OverviewService service;
    private final EnergyDataScopeService dataScope;

    public OverviewController(OverviewService service, EnergyDataScopeService dataScope) {
        this.service = service;
        this.dataScope = dataScope;
    }

    // REQ-057/058/060/062, REQ-019/029/039/040/042/053
    @GetMapping("/summary")
    public AjaxResult summary(
            @RequestParam(defaultValue = "today") String timeRange,
            @RequestParam(defaultValue = "ALL") String zone,
            @RequestParam(defaultValue = "ELEC") String energyType,
            Authentication authentication) {
        return AjaxResult.success(service.summary(timeRange, dataScope.zone(authentication, zone), energyType));
    }
}
