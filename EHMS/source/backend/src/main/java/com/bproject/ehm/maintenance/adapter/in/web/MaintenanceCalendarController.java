package com.bproject.ehm.maintenance.adapter.in.web;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.maintenance.application.WorkOrderRepairService.Repair;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.*;
import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import com.bproject.ehm.shared.error.ValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import org.springframework.context.annotation.Profile;
import java.time.*;
import java.util.*;

/** Read projection from authoritative aggregates, never a second calendar database. */
@RestController @Profile("server") @RequestMapping("/api/ehm/v1")
public class MaintenanceCalendarController {
    private final OpenGaussJsonStore store;private final AssetQueryFacade assets;
    public MaintenanceCalendarController(OpenGaussJsonStore store,AssetQueryFacade assets){this.store=store;this.assets=assets;}
    public record Entry(String id,String kind,String assetCode,String title,String recordId,String orderNo,
        Instant at,String status,String actor,String detail,boolean synthetic){}
    @GetMapping("/maintenance-calendar")
    public Map<String,Object> get(@RequestParam String assetCode,@RequestParam Instant from,@RequestParam Instant to,HttpServletRequest request){
        String code=assets.get(assetCode).code();
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(93))>0)throw new ValidationException("日历窗口须为正数且最多93天");
        Object actor=IamAuditIdentityFilter.actor(request);
        boolean orders=true,repairs=true;
        if(actor instanceof EhmSsoSessions.Actor a){
            orders=(a.context()==null||a.context().hasAction("EHMS:workorders","VIEW"))&&(a.admin()||a.permissions().contains("ehealth:maint:order:view"));
            var rule=EhmFeatureRoutes.resolve("GET","/api/ehm/v1/work-orders/UNBOUND/repair");
            repairs=orders&&(a.admin()||a.globalRead()||rule.nativeAllowed(a))&&(a.context()==null||rule.permits(a.context()));
        }
        return read(code,from,to,orders,repairs);
    }
    public Map<String,Object> read(String code,Instant from,Instant to,boolean includeOrders,boolean includeRepair){
        List<Entry> rows=new ArrayList<>();
        for(var p:store.findAll("maintenance-plan",MaintenancePlan.class))if(p.assetCode().equals(code))
            add(rows,new Entry("plan:"+p.planId(),"PLAN",code,p.name(),p.planId(),null,p.nextDueAt(),p.enabled()?"启用":"停用",p.ownerTeam(),p.triggerCondition(),synthetic(code)));
        for(var t:store.findAll("inspection-task",InspectionTask.class))if(t.assetCode().equals(code)){
            add(rows,new Entry("inspection:"+t.taskNo(),"INSPECTION",code,t.title(),t.taskNo(),null,t.scheduledAt(),t.status().label(),t.assignee(),"计划点检",synthetic(code)));
            if(t.submittedAt()!=null)add(rows,new Entry("inspection-result:"+t.taskNo(),"INSPECTION_RESULT",code,t.title(),t.taskNo(),null,t.submittedAt(),t.status().label(),t.operator(),t.conclusion(),synthetic(code)));
        }
        if(includeOrders)for(var w:store.findAll("work-order",WorkOrder.class))if(w.deviceCode().equals(code)){
            int i=0;for(var h:w.history())add(rows,new Entry("order:"+w.orderNo()+":"+i++,"WORK_ORDER",code,w.title(),w.orderNo(),w.orderNo(),h.occurredAt(),h.to().label(),h.operator(),h.reason(),synthetic(code)));
        }
        if(includeRepair){
            for(var r:store.findAll("work-order-repair",Repair.class))if(r.deviceCode().equals(code)){
                for(var x:r.executions())add(rows,new Entry("exec:"+x.recordId(),"EXECUTION",code,x.action(),x.recordId(),r.orderNo(),x.executedAt(),"已记录",x.operator(),x.result()+"；安全："+x.safetyConfirmation()+"；备件："+x.partsUsed(),synthetic(code)));
                for(var x:r.retests())add(rows,new Entry("retest:"+x.retestId(),"RETEST",code,x.pointCode(),x.retestId(),r.orderNo(),x.testedAt(),x.passed()?"通过":"不通过",x.operator(),x.beforeValue()+" → "+x.afterValue()+" "+x.unit()+"；"+x.criterion(),synthetic(code)));
                if(r.closedAt()!=null)add(rows,new Entry("close:"+r.orderNo(),"CLOSE",code,"维修复核",r.orderNo(),r.orderNo(),r.closedAt(),r.recoveryDecision(),r.reviewer(),r.conclusion()+"；"+r.recoveryReason(),synthetic(code)));
            }
            for(var c:store.findAll("maintenance-case",MaintenanceCase.class))if(c.deviceCode().equals(code)&&c.workOrderNo()!=null){
                for(var x:c.executionRecords())add(rows,new Entry("case-exec:"+x.recordId(),"EXECUTION",code,x.action(),x.recordId(),c.workOrderNo(),x.executedAt(),"已记录",x.operator(),x.result()+"；安全："+x.safetyConfirmation()+"；备件："+x.partsUsed(),synthetic(code)));
                for(var x:c.retests())add(rows,new Entry("case-retest:"+x.retestId(),"RETEST",code,x.pointCode(),x.retestId(),c.workOrderNo(),x.testedAt(),x.passed()?"通过":"不通过",x.operator(),x.beforeValue()+" → "+x.afterValue()+" "+x.unit()+"；"+x.criterion(),synthetic(code)));
            }
        }
        var all=rows.stream().filter(e->!e.at().isBefore(from)&&e.at().isBefore(to)).sorted(Comparator.comparing(Entry::at).reversed().thenComparing(Entry::id)).toList();
        return Map.of("assetCode",code,"from",from,"to",to,"events",all.stream().limit(2000).toList(),"truncated",all.size()>2000,
            "totalMatched",all.size(),"workOrdersIncluded",includeOrders,"repairDetailsIncluded",includeRepair,"readAt",Instant.now());
    }
    private static void add(List<Entry> rows,Entry e){if(e.at()!=null)rows.add(e);}
    private static boolean synthetic(String code){return code.startsWith("SAMPLE-")||code.startsWith("SIM-DEMO-")||code.startsWith("MQ-SIM-");}
}
