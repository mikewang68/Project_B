package com.mt.wms.agent;

import com.mt.wms.auth.WmsPrincipal;
import com.mt.wms.common.api.ApiResponse;
import com.mt.wms.common.web.RequestIdFilter;
import jakarta.servlet.http.*;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import java.util.*;
import static com.mt.wms.agent.AgentModels.*;

@RestController
@RequestMapping("/api/v1/agent")
@PreAuthorize("hasAuthority('agent:read') and hasAuthority('inventory:read')")
class AgentController {
    private final WarehouseAgentService service;private final AgentRepository repo;private final AgentAnalytics analytics;
    private final AgentPatrolService patrol;private final AgentModelClient model;
    AgentController(WarehouseAgentService service,AgentRepository repo,AgentAnalytics analytics,AgentPatrolService patrol,AgentModelClient model) {
        this.service=service;this.repo=repo;this.analytics=analytics;this.patrol=patrol;this.model=model;
    }
    WmsPrincipal p(Authentication a){return (WmsPrincipal)a.getPrincipal();}
    Scope scope(Authentication a,HttpSession s){return service.scope(p(a),s);}
    <T> ApiResponse<T> ok(T data,HttpServletRequest r){return ApiResponse.ok(data,r.getAttribute(RequestIdFilter.ATTRIBUTE).toString());}
    @GetMapping("/status") ApiResponse<?> status(HttpServletRequest r){return ok(Map.of("modelConfigured",model.configured(),"model",model.model(),"mailConfigured",patrol.mailReady(),"timezone","Asia/Shanghai","version","1.0"),r);}
    @PostMapping("/chat") ApiResponse<?> chat(@Valid @RequestBody ChatRequest body,Authentication a,HttpSession s,HttpServletRequest r){return ok(service.chat(body,p(a),s),r);}
    @GetMapping("/history") ApiResponse<?> history(Authentication a,HttpSession s,HttpServletRequest r){return ok(repo.tasks(scope(a,s)),r);}
    @GetMapping("/utilization") ApiResponse<?> utilization(Authentication a,HttpSession s,HttpServletRequest r){return ok(analytics.utilization(scope(a,s),false),r);}
    @GetMapping("/alerts") ApiResponse<?> alerts(Authentication a,HttpSession s,HttpServletRequest r){return ok(repo.alerts(scope(a,s)),r);}
    @PostMapping("/alerts/{id}/read") ApiResponse<?> read(@PathVariable long id,Authentication a,HttpSession s,HttpServletRequest r){repo.readAlert(scope(a,s),id);return ok(null,r);}
    @GetMapping("/schedules") ApiResponse<?> schedules(Authentication a,HttpSession s,HttpServletRequest r){return ok(repo.schedules(scope(a,s)),r);}
    @PreAuthorize("hasAuthority('agent:manage') and hasAuthority('inventory:read')")
    @PostMapping("/schedules") ApiResponse<?> create(@Valid @RequestBody ScheduleRequest body,Authentication a,HttpSession s,HttpServletRequest r){patrol.save(scope(a,s),null,body);return ok(null,r);}
    @PreAuthorize("hasAuthority('agent:manage') and hasAuthority('inventory:read')")
    @PutMapping("/schedules/{id}") ApiResponse<?> update(@PathVariable long id,@Valid @RequestBody ScheduleRequest body,Authentication a,HttpSession s,HttpServletRequest r){patrol.save(scope(a,s),id,body);return ok(null,r);}
    @PreAuthorize("hasAuthority('agent:manage') and hasAuthority('inventory:read')")
    @PostMapping("/schedules/{id}/run") ApiResponse<?> run(@PathVariable long id,Authentication a,HttpSession s,HttpServletRequest r){return ok(patrol.runNow(scope(a,s),id),r);}
    @GetMapping("/mails") ApiResponse<?> mails(Authentication a,HttpSession s,HttpServletRequest r){return ok(patrol.mails(scope(a,s)),r);}
    @PreAuthorize("hasAuthority('agent:manage') and hasAuthority('inventory:read')")
    @PostMapping("/mails/{id}/retry") ApiResponse<?> retry(@PathVariable long id,Authentication a,HttpSession s,HttpServletRequest r){patrol.retryMail(scope(a,s),id);return ok(null,r);}
    @GetMapping("/weights") ApiResponse<?> weights(Authentication a,HttpSession s,HttpServletRequest r){return ok(repo.weightData(scope(a,s)),r);}
    @PreAuthorize("hasAuthority('master:write') and hasAuthority('inventory:read')")
    @PutMapping("/weights/{id}") ApiResponse<?> weight(@PathVariable long id,@Valid @RequestBody WeightRequest body,Authentication a,HttpSession s,HttpServletRequest r){repo.weight(scope(a,s),id,body.weightKg());return ok(null,r);}
}
