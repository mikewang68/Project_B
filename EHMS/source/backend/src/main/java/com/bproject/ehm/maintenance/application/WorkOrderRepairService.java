package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.caseflow.domain.model.*;
import com.bproject.ehm.caseflow.ports.MaintenanceCaseRepository;
import com.bproject.ehm.maintenance.ports.WorkOrderRepository;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.shared.error.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;

/** The same evidence gate for manual, inspection and health-advice orders without an alarm case. */
@Service @Profile("server")
public class WorkOrderRepairService {
    private final OpenGaussJsonStore store;
    private final WorkOrderRepository orders;
    private final MaintenanceCaseRepository cases;
    private final MeasurementPointRepository points;
    public WorkOrderRepairService(OpenGaussJsonStore store,WorkOrderRepository orders,MaintenanceCaseRepository cases,MeasurementPointRepository points){
        this.store=store;this.orders=orders;this.cases=cases;this.points=points;
    }
    public record Repair(String orderNo,String deviceCode,List<String> requiredPoints,List<ExecutionRecord> executions,
        List<RetestRecord> retests,String conclusion,String recoveryDecision,String recoveryReason,String reviewer,Instant closedAt){
        public Repair{requiredPoints=requiredPoints==null?List.of():List.copyOf(requiredPoints);executions=executions==null?List.of():List.copyOf(executions);retests=retests==null?List.of():List.copyOf(retests);}
    }
    public Repair get(String no){var order=order(no);return store.find("work-order-repair",no,Repair.class)
        .orElse(new Repair(no,order.deviceCode(),List.of(),List.of(),List.of(),null,null,null,null,null));}
    private WorkOrder order(String no){return orders.findByOrderNo(no).orElseThrow(()->new ResourceNotFoundException("未找到工单"));}
    private WorkOrder lock(String no){store.lockAggregate("work-order",no);var order=order(no);
        if(cases.findByWorkOrderNo(no).isPresent())throw new DomainConflictException("此工单已有关联告警档案，请在该档案中完成执行与复测");
        if(order.status()==WorkOrderStatus.CLOSED||order.status()==WorkOrderStatus.CANCELLED)throw new DomainConflictException("已关闭或取消的工单不能修改维修记录");
        return order;}
    @Transactional public Repair plan(String no,List<String> codes){var order=lock(no);var r=get(no);
        if(!r.retests().isEmpty())throw new DomainConflictException("已有复测结果，不能覆盖必测计划");
        if(codes==null||codes.isEmpty()||codes.size()>100)throw new ValidationException("请选择1至100个必测测点");
        var selected=codes.stream().map(com.bproject.ehm.asset.domain.model.MeasurementPoint::normalizeCode).distinct().toList();
        selected.forEach(code->point(order,code,null));
        return save(new Repair(no,order.deviceCode(),selected,r.executions(),r.retests(),null,null,null,null,null));}
    @Transactional public Repair execute(String no,String action,String result,String safety,String parts,String actor){var order=lock(no);var r=get(no);
        if(order.status()!=WorkOrderStatus.IN_PROGRESS)throw new DomainConflictException("工单须为执行中才能记录维修");
        var rows=new ArrayList<>(r.executions());rows.add(new ExecutionRecord("EXE-"+UUID.randomUUID(),required(action,"执行内容"),required(result,"执行结果"),
            required(safety,"安全确认"),required(parts,"备件及数量，未用请明确填写"),actor,Instant.now()));
        return save(new Repair(no,order.deviceCode(),r.requiredPoints(),rows,r.retests(),null,null,null,null,null));}
    @Transactional public Repair retest(String no,String code,Double before,Double after,String unit,String criterion,boolean passed,String actor){var order=lock(no);var r=get(no);
        if(order.status()!=WorkOrderStatus.WAITING_VERIFY||r.executions().isEmpty())throw new DomainConflictException("须完成执行记录并进入待复测状态");
        if(!r.requiredPoints().contains(code))throw new ValidationException("测点不在本工单必测计划中");point(order,code,unit);
        if(before==null||after==null||!Double.isFinite(before)||!Double.isFinite(after))throw new ValidationException("请填写有限的维修前后数值");
        var rows=new ArrayList<>(r.retests());rows.add(new RetestRecord("RET-"+UUID.randomUUID(),code,before,after,unit,required(criterion,"验收判据"),passed,actor,Instant.now()));
        return save(new Repair(no,order.deviceCode(),r.requiredPoints(),r.executions(),rows,null,null,null,null,null));}
    @Transactional public Repair close(String no,String conclusion,String decision,String reason,String actor){var order=lock(no);var r=get(no);
        if(order.status()!=WorkOrderStatus.WAITING_VERIFY)throw new DomainConflictException("工单必须处于待复测状态");
        requirePassing(r);
        if(!Set.of("AVAILABLE","RESTRICTED","NOT_RESTORED").contains(decision==null?"":decision))throw new ValidationException("请选择设备恢复决定");
        var now=Instant.now();var done=new Repair(no,order.deviceCode(),r.requiredPoints(),r.executions(),r.retests(),required(conclusion,"复核结论"),decision,required(reason,"恢复依据"),actor,now);
        save(done);orders.save(order.transitionTo(WorkOrderStatus.CLOSED,actor,"量化复测与复核完成；恢复决定："+decision,now));return done;
    }
    public static void requirePassing(Repair r){
        if(r.executions().isEmpty()||r.requiredPoints().isEmpty())throw new DomainConflictException("缺少执行记录或必测计划");
        var latest=new HashMap<String,RetestRecord>();Instant start=r.executions().get(r.executions().size()-1).executedAt();
        for(var row:r.retests())if(!row.testedAt().isBefore(start))latest.put(row.pointCode(),row);
        if(r.requiredPoints().stream().anyMatch(code->!latest.containsKey(code)||!latest.get(code).passed()))
            throw new DomainConflictException("本维修轮次所有必测项的最新结果须全部通过");
    }
    private void point(WorkOrder order,String code,String unit){var p=points.findByCode(code).orElseThrow(()->new ValidationException("未登记的复测测点"));
        if(p.archived()||!p.enabled()||!p.assetCode().equals(order.deviceCode()))throw new ValidationException("测点必须属于该设备且启用");
        if(unit!=null&&!Objects.equals(unit,p.unit()))throw new ValidationException("复测单位与测点登记不一致");}
    private Repair save(Repair r){return store.save("work-order-repair",r.orderNo(),r);}
    private static String required(String v,String name){if(v==null||v.isBlank())throw new ValidationException(name+"不能为空");return v.trim();}
}
