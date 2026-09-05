package com.bdemo.energy.alert;

import com.bdemo.auth.AuthenticatedUser;
import com.bdemo.common.AjaxResult;
import com.bdemo.config.EnergyDataScopeService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;

@RestController
@RequestMapping("/alerts")
public class AlertController {
    private final AlertService service;
    private final EnergyDataScopeService dataScope;

    public AlertController(AlertService service, EnergyDataScopeService dataScope) {
        this.service = service;
        this.dataScope = dataScope;
    }

    // REQ-039/042/044: filterable events and retrospective metrics come from persisted rule results.
    @GetMapping
    public AjaxResult list(@RequestParam(required = false) String level,
                           @RequestParam(required = false) String status,
                           @RequestParam(required = false) String ruleCode,
                           @RequestParam(defaultValue = "ALL") String zone,
                           @RequestParam(defaultValue = "1") int pageNum,
                           @RequestParam(defaultValue = "20") int pageSize,
                           Authentication authentication) {
        return AjaxResult.success(service.list(level, status, ruleCode,
                dataScope.zone(authentication, zone), pageNum, pageSize)).add("msg", "alerts");
    }

    @GetMapping("/statistics")
    public AjaxResult statistics(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime periodStart,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime periodEnd,
            @RequestParam(defaultValue = "ALL") String zone,
            @RequestParam(required = false) String ruleCode,
            @RequestParam(required = false) String level,
            @RequestParam(required = false) String status,
            Authentication authentication) {
        return AjaxResult.success(service.statistics(periodStart, periodEnd,
                dataScope.zone(authentication, zone), ruleCode, level, status))
                .add("msg", "alert statistics");
    }

    @GetMapping("/{eventId}")
    public AjaxResult detail(@PathVariable long eventId, Authentication authentication) {
        dataScope.checkAlert(authentication, eventId);
        return AjaxResult.success(service.detail(eventId)).add("msg", "alert detail");
    }

    // REQ-041: operator identity is accepted only from the verified JWT principal.
    @PostMapping("/{eventId}/transition")
    public AjaxResult transition(@PathVariable long eventId, @RequestBody TransitionRequest body,
                                 @AuthenticationPrincipal AuthenticatedUser user) {
        return AjaxResult.success(service.transition(eventId, body, user.userName()))
                .add("msg", "alert transitioned");
    }

    public record TransitionRequest(String toStatus, String assignedTo, String remark,
                                    String closeType, String closeReason) {}
}
