package com.bproject.ehm.maintenance.adapter.in.web;
import com.bproject.ehm.maintenance.application.WorkOrderRepairService;
import com.bproject.ehm.platform.security.EhmOperationIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @Profile("server") @RequestMapping("/api/ehm/v1/work-orders/{no}/repair")
public class WorkOrderRepairController {
    private final WorkOrderRepairService service;
    public WorkOrderRepairController(WorkOrderRepairService service){this.service=service;}
    @GetMapping public WorkOrderRepairService.Repair get(@PathVariable String no){return service.get(no);}
    @PostMapping("/plan") public WorkOrderRepairService.Repair plan(@PathVariable String no,@RequestBody Plan value,HttpServletRequest http){EhmOperationIdentity.require(http);return service.plan(no,value.pointCodes());}
    @PostMapping("/executions") public WorkOrderRepairService.Repair execute(@PathVariable String no,@RequestBody Execution value,HttpServletRequest http){return service.execute(no,value.action(),value.result(),value.safetyConfirmation(),value.partsUsed(),EhmOperationIdentity.require(http));}
    @PostMapping("/retests") public WorkOrderRepairService.Repair retest(@PathVariable String no,@RequestBody Retest value,HttpServletRequest http){return service.retest(no,value.pointCode(),value.beforeValue(),value.afterValue(),value.unit(),value.criterion(),value.passed(),EhmOperationIdentity.require(http));}
    @PostMapping("/close") public WorkOrderRepairService.Repair close(@PathVariable String no,@RequestBody Closure value,HttpServletRequest http){return service.close(no,value.conclusion(),value.recoveryDecision(),value.recoveryReason(),EhmOperationIdentity.require(http));}
    public record Plan(List<String> pointCodes){}
    public record Execution(String action,String result,String safetyConfirmation,String partsUsed){}
    public record Retest(String pointCode,Double beforeValue,Double afterValue,String unit,String criterion,boolean passed){}
    public record Closure(String conclusion,String recoveryDecision,String recoveryReason){}
}
