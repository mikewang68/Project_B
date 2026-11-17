package com.bproject.ehm.maintenance.adapter.in.web;
import com.bproject.ehm.maintenance.application.MaintenanceReminderService;
import com.bproject.ehm.platform.security.EhmOperationIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.*;
@RestController @Profile("server") @RequestMapping("/api/ehm/v1")
public class MaintenanceReminderController {
    private final MaintenanceReminderService service;
    public MaintenanceReminderController(MaintenanceReminderService service){this.service=service;}
    @GetMapping("/devices/{code}/maintenance-reminders") public List<MaintenanceReminderService.Reminder> list(@PathVariable String code){return service.list(code);}
    @PostMapping("/devices/{code}/maintenance-reminders/scan") public Map<String,Object> scan(@PathVariable String code,HttpServletRequest http){return service.scan(code,EhmOperationIdentity.require(http));}
    @PostMapping("/maintenance-reminders/{id}/complete") public MaintenanceReminderService.Reminder complete(@PathVariable String id,@RequestBody Completion value,HttpServletRequest http){
        return service.complete(id,value.inspectionTaskNo(),value.completedReading(),value.coverageStatement(),EhmOperationIdentity.require(http));}
    public record Completion(String inspectionTaskNo,Double completedReading,String coverageStatement){}
}
