package com.bproject.ehm.platform.audit.adapter.in.web;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
@RestController @RequestMapping("/api/ehm/v1/system")
public class AuditDeliveryController {
    private final ObjectProvider<EhmAuditRelay> relay;
    private final ObjectProvider<com.bproject.ehm.platform.audit.application.AuditConsoleService> console;
    public AuditDeliveryController(ObjectProvider<EhmAuditRelay> relay,ObjectProvider<com.bproject.ehm.platform.audit.application.AuditConsoleService> console) { this.relay=relay; this.console=console; }
    @GetMapping("/audit-delivery") public Map<String,Object> status(HttpServletRequest request) {
        var target=relay.getIfAvailable();if(target==null)return Map.of("durableQueue",false,"deliveryConfigured",false);
        var result=new java.util.LinkedHashMap<>(target.status());var viewer=console.getIfAvailable();
        result.put("recentEvents",IamAuditIdentityFilter.actor(request)==null||viewer==null?java.util.List.of():viewer.query(request,0,20,null,null,null,null,null,null,null,null).get("items"));
        return result;
    }
    public record ClientEvent(String menuId,String action) {}
    @PostMapping("/client-events") public Map<String,Object> client(@RequestBody ClientEvent event,HttpServletRequest request) {
        var target=relay.getIfAvailable(); if(target==null)throw new IllegalStateException("Server profile required for durable audit");
        target.clientEvent(request,event.menuId(),event.action());
        request.setAttribute(EhmAuditRelay.BUSINESS_RECORDED,true);
        return Map.of("stored",true,"sourceKind","CLIENT","outcome","STARTED");
    }
}
