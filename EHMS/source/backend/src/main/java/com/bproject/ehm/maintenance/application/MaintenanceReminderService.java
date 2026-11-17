package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.asset.application.DeviceDossierService;
import com.bproject.ehm.asset.domain.model.DeviceDossier;
import com.bproject.ehm.asset.ports.DeviceDossierRepository;
import com.bproject.ehm.maintenance.ports.InspectionTaskRepository;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.workbench.ports.UserTaskRepository;
import com.bproject.ehm.shared.error.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/** Persisted reminder, not an automatic maintenance order or equipment command. */
@Service @Profile("server")
public class MaintenanceReminderService {
    private final OpenGaussJsonStore store;
    private final DeviceDossierService dossiers;
    private final DeviceDossierRepository repository;
    private final UserTaskRepository tasks;
    private final InspectionTaskRepository inspections;
    public MaintenanceReminderService(OpenGaussJsonStore store,DeviceDossierService dossiers,DeviceDossierRepository repository,
        UserTaskRepository tasks,InspectionTaskRepository inspections){
        this.store=store;this.dossiers=dossiers;this.repository=repository;this.tasks=tasks;this.inspections=inspections;
    }
    public record Reminder(String reminderId,String assetCode,String taskNo,String status,String sourceDocumentId,
        String sourceSection,String sourceKind,String unit,double interval,Instant cycleStartedAt,Double cycleReading,
        String responsibleTeam,String createdBy,Instant createdAt,String inspectionTaskNo,String completedBy,Instant completedAt,String completionBasis){}
    public List<Reminder> list(String code){dossiers.get(code);return store.findAll("maintenance-reminder",Reminder.class).stream()
        .filter(r->r.assetCode().equalsIgnoreCase(code)).sorted(Comparator.comparing(Reminder::createdAt).reversed()).toList();}
    @Transactional public Map<String,Object> scan(String code,String actor){
        var d=dossiers.get(code);store.lockAggregate("asset",d.assetCode());d=dossiers.get(code);
        var result=dossiers.due(code);String status=result.get("status").toString();
        if(!Set.of("UPCOMING","DUE").contains(status))return Map.of("created",false,"due",result);
        var b=d.maintenanceBasis();if(b.ownerTeam()==null||b.ownerTeam().isBlank())throw new ValidationException("请先为维护依据登记责任班组");
        String id=key(d.assetCode()+"|"+b.sourceDocumentId()+"|"+b.sourceSection()+"|"+b.unit()+"|"+b.interval()+"|"+b.lastCompletedAt()+"|"+b.lastServiceReading());
        var existing=store.find("maintenance-reminder",id,Reminder.class);
        if(existing.isPresent())return Map.of("created",false,"due",result,"reminder",existing.get());
        var now=Instant.now();String no="MREM-"+id.substring(0,20).toUpperCase(Locale.ROOT);
        tasks.save(UserTask.create(no,"维护到期核查","MAINTENANCE_REMINDER",id,d.assetCode()+"维护"+(status.equals("DUE")?"到期":"即将到期"),
            "依据："+b.sourceSection()+"；周期"+b.interval()+b.unit()+"；请安排点检并关联实际执行结果，不自动下发维修或设备控制。",
            d.assetCode(),b.ownerTeam(),b.ownerTeam(),status.equals("DUE")?"P1 高":"P2 中",now,now));
        var reminder=new Reminder(id,d.assetCode(),no,"OPEN",b.sourceDocumentId(),b.sourceSection(),b.sourceKind(),b.unit(),b.interval(),
            b.lastCompletedAt(),b.lastServiceReading(),b.ownerTeam(),actor,now,null,null,null,null);
        store.save("maintenance-reminder",id,reminder);return Map.of("created",true,"due",result,"reminder",reminder);
    }
    @Transactional public Reminder complete(String id,String inspectionNo,Double reading,String coverage,String actor){
        var r=store.find("maintenance-reminder",id,Reminder.class).orElseThrow(()->new ResourceNotFoundException("维护提醒不存在"));
        store.lockAggregate("asset",r.assetCode());r=store.find("maintenance-reminder",id,Reminder.class).orElseThrow();
        if(r.status().equals("COMPLETED")){
            if(!Objects.equals(r.inspectionTaskNo(),inspectionNo))throw new DomainConflictException("此提醒已关联另一条完成结果，不能覆盖");
            return r;
        }
        if(coverage==null||coverage.isBlank())throw new ValidationException("请说明本次点检覆盖维护依据条款的复核依据，不能仅凭同设备合格就完成维护");
        var inspection=inspections.findByTaskNo(inspectionNo).orElseThrow(()->new ValidationException("请选择实际已完成的点检任务"));
        if(!inspection.assetCode().equals(r.assetCode())||inspection.status()!=com.bproject.ehm.maintenance.domain.model.InspectionTaskStatus.CLOSED||inspection.submittedAt()==null||!inspection.failedItems().isEmpty()
            ||inspection.submittedAt().isBefore(r.createdAt()))throw new DomainConflictException("点检须为本设备、本提醒生成后的合格执行结果");
        if(store.findAll("maintenance-reminder",Reminder.class).stream().anyMatch(x->inspectionNo.equals(x.inspectionTaskNo())))
            throw new DomainConflictException("该点检结果已经用于完成其他维护周期");
        var d=dossiers.get(r.assetCode());var b=d.maintenanceBasis();
        if(b==null||!Objects.equals(b.sourceDocumentId(),r.sourceDocumentId())||!Objects.equals(b.lastCompletedAt(),r.cycleStartedAt())
            ||!Objects.equals(b.lastServiceReading(),r.cycleReading())||!Objects.equals(b.sourceSection(),r.sourceSection())
            ||!b.unit().equals(r.unit())||b.interval()!=r.interval())throw new DomainConflictException("维护依据或周期已经变更，请核查原提醒，不能覆盖新周期");
        if(!b.unit().equals("DAYS")&&(reading==null||!Double.isFinite(reading)||reading<Math.max(b.currentReading()==null?0:b.currentReading(),b.lastServiceReading()==null?0:b.lastServiceReading())))
            throw new ValidationException("工时/次数维护须填写不小于当前累计值的完成读数");
        var now=Instant.now();var next=new DeviceDossier.MaintenanceBasis(b.sourceDocumentId(),b.sourceSection(),b.sourceKind(),b.unit(),b.interval(),b.reminderLead(),
            inspection.submittedAt(),b.unit().equals("DAYS")?b.lastServiceReading():reading,b.unit().equals("DAYS")?b.currentReading():reading,b.ownerTeam());
        repository.save(new DeviceDossier(d.assetCode(),d.manufacturer(),d.model(),d.serialNumber(),d.commissionedOn(),d.processPosition(),d.externalCode(),d.notes(),
            next,d.documents(),d.version()+1,now),d.version());
        var task=tasks.findByTaskNo(r.taskNo()).orElseThrow();tasks.save(task.complete(actor,now));
        var done=new Reminder(r.reminderId(),r.assetCode(),r.taskNo(),"COMPLETED",r.sourceDocumentId(),r.sourceSection(),r.sourceKind(),r.unit(),r.interval(),
            r.cycleStartedAt(),r.cycleReading(),r.responsibleTeam(),r.createdBy(),r.createdAt(),inspectionNo,actor,now,coverage.trim());
        return store.save("maintenance-reminder",id,done);
    }
    private static String key(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(Exception e){throw new IllegalStateException(e);}}
}
