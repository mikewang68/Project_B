package com.bproject.ehm.simulation;

import com.bproject.ehm.asset.application.DeviceDossierService;
import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.asset.domain.model.DeviceDossier.*;
import com.bproject.ehm.monitoring.application.*;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.maintenance.application.*;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.caseflow.application.*;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.*;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.context.annotation.Profile;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.support.TransactionTemplate;
import org.slf4j.LoggerFactory;
import java.time.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Opt-in persisted DEMO scenario. Does not migrate legacy rows or impersonate human approvals. */
@Service @Profile("server")
@ConditionalOnProperty(name="ehm.demo-workspace.enabled",havingValue="true")
public class DemoWorkspaceService {
    public static final String ACTOR="[虚拟]演示场景服务", MARKER="integrated-demo-v1";
    private final OpenGaussJsonStore store; private final TransactionTemplate tx;
    private final SimulationService simulator; private final DataQualityApplicationService quality;
    private final com.bproject.ehm.health.application.HealthAssessmentApplicationService health;
    private final DeviceDossierService dossiers; private final MaintenanceReminderService reminders;
    private final WorkOrderApplicationService orders; private final WorkOrderRepairService repairs;
    private final MaintenanceProgramApplicationService program; private final CaseEvidenceService evidence;
    private final EhmAuditRelay audit; private final TelemetrySeriesPort telemetry;
    private final ObjectMapper mapper;
    public DemoWorkspaceService(OpenGaussJsonStore store,TransactionTemplate tx,SimulationService legacy,
        DataQualityApplicationService quality,com.bproject.ehm.health.application.HealthAssessmentApplicationService health,
        DeviceDossierService dossiers,MaintenanceReminderService reminders,WorkOrderApplicationService orders,
        WorkOrderRepairService repairs,MaintenanceProgramApplicationService program,CaseEvidenceService evidence,
        EhmAuditRelay audit,TelemetrySeriesPort telemetry,ObjectMapper mapper) {
        this.store=store;this.tx=tx;this.simulator=legacy.regionalDemo(mapper);this.quality=quality;this.health=health;
        this.dossiers=dossiers;this.reminders=reminders;this.orders=orders;this.repairs=repairs;this.program=program;
        this.evidence=evidence;this.audit=audit;this.telemetry=telemetry;this.mapper=mapper;
    }
    public record RunState(long ticks,Instant lastTickAt,String status,String lastError,boolean synthetic){}
    private static boolean localSynthetic(MeasurementPoint p){return "SYNTHETIC".equals(p.sourceProtocol())&&("sample://"+p.code()).equals(p.sourceAddress());}
    public static boolean eligible(Asset a){return !a.archived()&&(a.code().startsWith("SAMPLE-LIVE-")||a.code().startsWith("MQ-SIM-")||a.code().startsWith("SIM-DEMO-"));}
    @Scheduled(fixedDelayString="${ehm.demo-workspace.interval-ms:60000}",initialDelayString="${ehm.demo-workspace.initial-delay-ms:15000}")
    public synchronized void scheduled() {
        try {runOnce();} catch(Exception error) {
            // Do not leak configuration, SQL payloads or credentials into public logs.
            LoggerFactory.getLogger(getClass()).error("DEMO_WORKSPACE_ROUND_FAILED: {}",error.getClass().getSimpleName());
            try(var p=ProjectContext.bind(SimulationRunScope.PROJECT);var scope=SimulationRunScope.bindDemo()) {
                tx.executeWithoutResult(s->{var old=store.find("demo-workspace","activity",RunState.class).orElse(new RunState(0,null,"","",true));
                    store.save("demo-workspace","activity",new RunState(old.ticks(),old.lastTickAt(),"FAILED",error.getClass().getSimpleName(),true));});
            } catch(Exception ignored){}
        }
    }
    void runOnce() throws Exception {
        try(var project=ProjectContext.bind(SimulationRunScope.PROJECT);var scope=SimulationRunScope.bindDemo()) {
            simulator.initializeController();
            boolean first=store.find("demo-workspace",MARKER,Object.class).isEmpty();
            if(first) {
                simulator.install(SimulationService.CONFIRMATION);
                simulator.running(false,SimulationService.CONFIRMATION);
                simulator.configure(new SimulationService.Settings(60,.25,40,.8,false),SimulationService.CONFIRMATION);
                simulator.running(true,SimulationService.CONFIRMATION);
                // Both rounds use current write times, not fabricated past operational timestamps.
                simulator.tick("demo-bootstrap-round-1",SimulationService.CONFIRMATION);
                simulator.tick("demo-bootstrap-round-2",SimulationService.CONFIRMATION);
                tx.executeWithoutResult(s->initializeData());
            } else simulator.scheduledTick();
            tx.executeWithoutResult(s->refreshExtrasAndReminders());
        }
    }
    private void initializeData() {
        Instant now=Instant.now();int index=0;
        for(var asset:store.findAll("asset",Asset.class).stream().filter(DemoWorkspaceService::eligible).sorted(Comparator.comparing(Asset::code)).toList()) {
            ensureStructure(asset,now);
            var points=points(asset.code());
            // Persisted synthetic historical source samples; receipt is NOW and not backdated.
            for(var point:points) if(localSynthetic(point)) for(int i=24;i>0;i--) {
                double upper=point.upperLimit()==null?100:point.upperLimit();
                double value=Math.round(upper*(.40+.1*Math.sin(i*.55))*100)/100d;
                var sample=new TelemetrySeriesPort.TelemetrySample(point.code(),asset.code(),point.metric(),point.unit(),value,
                    i==12?"SYNTHETIC_BAD":"SYNTHETIC_GOOD",now.minusSeconds(i*300L),now,null,true,"DEMO_REPLAY_INITIALIZATION");
                telemetry.write(sample);
            }
            ensureDossier(asset,now);
            if(index<6) seedRepair(asset,points,index,now);
            if(index<3) seedInspection(asset,index);
            index++;
        }
        var alarm=store.findAll("maintenance-case",MaintenanceCase.class).stream().filter(c->c.alarmNo().startsWith("SAMPLE-LIVE-")).findFirst().orElse(null);
        if(alarm!=null) {
            var point=points(alarm.deviceCode()).get(0);
            var window=evidence.capture(alarm.alarmNo(),point.code(),now.minusSeconds(7200),Instant.now(),ACTOR);
            var diagnosed=store.find("maintenance-case",alarm.alarmNo(),MaintenanceCase.class).orElseThrow().diagnose("[虚拟]周期核查样例，待现场人员确认","虚拟累计工时达到演示阈值","规则演示",
                "历史证据窗口："+window.evidenceId()+"；不是现场故障认定",ACTOR,Instant.now());
            store.save("maintenance-case",alarm.alarmNo(),diagnosed);
        }
        store.save("demo-workspace",MARKER,Map.of("initializedAt",Instant.now(),"source","SIMULATED","assetCount",index,"humanAcceptance",false));
        audit.simulationEvent("DEMO-INIT-"+UUID.randomUUID(),"CREATE",MARKER,"独立演示区初始化关联资料、测点历史、维修样例；全部为虚拟，不替代人工验收");
    }
    private List<MeasurementPoint> points(String asset){return store.findAll("measurement-point",MeasurementPoint.class).stream().filter(p->p.assetCode().equals(asset)&&p.enabled()&&!p.archived()).toList();}
    private void ensureStructure(Asset asset,Instant now) {
        String code=asset.code(),component=code+"-CORE";
        if(store.find("component",component,Object.class).isEmpty())
            store.save("component",component,Component.create(component,code,null,"[虚拟]监测总成","虚拟部件","","","SIMULATED","演示配置","模拟位置",LocalDate.now().toString(),"在役",now));
        if(points(code).stream().noneMatch(DemoWorkspaceService::localSynthetic)) for(var m:SimulationCatalog.metrics("ROTATING")) {
            String id="DEMO-POINT-"+code+"-"+m.code();
            if(store.find("measurement-point",id,Object.class).isEmpty())store.save("measurement-point",id,MeasurementPoint.create(id,code,component,
                "[虚拟]"+m.name(),m.name(),m.unit(),"SYNTHETIC","sample://"+id,60,0d,m.upper(),true,now));
        }
        String plan=code+"-PLAN";
        if(store.find("maintenance-plan",plan,Object.class).isEmpty())store.save("maintenance-plan",plan,MaintenancePlan.create(plan,code,asset.name(),component,
            "[虚拟]例行检查","PERIODIC",1,"演示规程每日核查，实际操作由人工确认",
            List.of(new InspectionTemplateItem("STATE","状态与外观","虚拟场景核查","模拟检查无异常","—",true)),"虚拟运维班",now.plusSeconds(86400),now));
        String task=code+"-DEMO-INS";
        if(store.findAll("inspection-task",InspectionTask.class).stream().noneMatch(t->t.planId().equals(plan)&&Set.of(InspectionTaskStatus.PLANNED,InspectionTaskStatus.IN_PROGRESS).contains(t.status())))
            store.save("inspection-task",task,InspectionTask.create(task,store.find("maintenance-plan",plan,MaintenancePlan.class).orElseThrow(),now,"虚拟运维班",now));
    }
    private void ensureDossier(Asset asset,Instant now) {
        var d=dossiers.get(asset.code());
        if(!d.documents().isEmpty()||d.maintenanceBasis()!=null)return; // Never overwrite user documentation.
        String text="设备："+asset.code()+"\n性质：虚拟演示，非厂家资料\n版本：DEMO-20261117\n条款1：每1天核查状态和外观；提前0.25天提醒。\n条款2：实际维修后按所选启用测点填写前后值和判据，失败不得关闭。\n所有阈值为演示假设，不是设计寿命、现场标准或设备控制指令。\n上次完成时间为场景初始假设，不是人工历史签核。";
        d=dossiers.upload(asset.code(),d.version(),"[虚拟]维护与复测演示规程","MAINTENANCE","DEMO-20261117","demo-maintenance.txt",text.getBytes(StandardCharsets.UTF_8));
        var basis=new MaintenanceBasis(d.documents().get(d.documents().size()-1).id(),"DEMO-20261117 条款1；虚拟初始基准","SIMULATED","DAYS",1,.25,now.minusSeconds(2*86400),null,null,"虚拟运维班");
        dossiers.update(asset.code(),new DeviceDossier(d.assetCode(),d.manufacturer(),d.model(),d.serialNumber(),d.commissionedOn(),
            "独立演示区",d.externalCode(),"虚拟设备场景。部件、量程和周期为演示配置，不代表厂家参数。",basis,d.documents(),d.version(),now));
    }
    private void seedRepair(Asset a,List<MeasurementPoint> points,int index,Instant now) {
        String no="DEMO-ACCEPT-"+a.code()+"-WO";
        if(store.find("work-order",no,Object.class).isPresent()||points.isEmpty())return;
        store.save("work-order",no,WorkOrder.create(no,a.code(),a.name(),"[虚拟]维修流程样例 "+(index+1),"P2 中","虚拟运维班",
            "DEMO_WORKSPACE","虚拟记录，用于核验流程；不是实际故障或人工维修签名",LocalDate.now()+" 演示窗口",ACTOR,now));
        if(index==0)return;
        orders.transition(no,"APPROVED",ACTOR,"[虚拟]场景预置审批阶段");
        if(index==1)return;
        orders.transition(no,"IN_PROGRESS",ACTOR,"[虚拟]场景预置执行阶段");
        var p=points.get(0);repairs.plan(no,List.of(p.code()));
        repairs.execute(no,"[虚拟]模拟核查与调整","[虚拟]处置记录样例","无实际设备；未下发PLC；演示安全确认","未使用实际备件",ACTOR);
        if(index==2)return;
        orders.transition(no,"WAITING_VERIFY",ACTOR,"[虚拟]场景预置待复测阶段");
        if(index==3)return;
        double upper=p.upperLimit()==null?100:p.upperLimit();
        repairs.retest(no,p.code(),upper*.9,index==4?upper*.4:upper*.9,p.unit(),"虚拟判据：有效复测低于演示量程65%；非现场检修标准",index==4,ACTOR);
        if(index==4){
            repairs.close(no,"[虚拟]样例复测通过","RESTRICTED","虚拟流程关闭；不代表真实设备获准启动",ACTOR);
            var knowledge=com.bproject.ehm.reliability.domain.model.KnowledgeCase.create(no,"SAMPLE-LIVE-CYCLE",a.type(),"虚拟监测对象",
                "[虚拟]维修与复测样例","演示采样达到假设提醒阈值","虚拟工况，不是现场故障认定",List.of("核对测点质量","记录实际处置","逐点复测和复核"),
                "演示处置记录","本轮复测与维修档案可回查",no,Instant.now());
            store.save("knowledge-case",no,knowledge);
        }
    }
    private void seedInspection(Asset a,int index) {
        var task=store.findAll("inspection-task",InspectionTask.class).stream().filter(t->t.assetCode().equals(a.code())&&t.status()==InspectionTaskStatus.PLANNED).findFirst().orElse(null);
        if(task==null)return;
        var scanned=reminders.scan(a.code(),ACTOR);
        program.startTask(task.taskNo(),ACTOR);if(index==2)return;
        var results=task.checklist().stream().map(i->new InspectionTask.ItemSubmission(i.itemId(),index==0?"[虚拟]无异常":"[虚拟]发现松动",index==0?"PASS":"FAIL","模拟场景预置，不是实际人员点检")).toList();
        program.submitTask(task.taskNo(),results,"[虚拟]点检流程样例",ACTOR,"一般",index==0?"":"[虚拟]外观松动，待人工判断");
        if(index==0&&scanned.get("reminder") instanceof MaintenanceReminderService.Reminder reminder)
            reminders.complete(reminder.reminderId(),task.taskNo(),null,"[虚拟]状态与外观检查覆盖演示规程条款1；非现场维护回执",ACTOR);
    }
    private void refreshExtrasAndReminders() {
        Instant now=Instant.now();var old=store.find("demo-workspace","activity",RunState.class).orElse(new RunState(0,null,"","",true));
        for(var a:store.findAll("asset",Asset.class).stream().filter(DemoWorkspaceService::eligible).toList()) {
            if(!a.code().startsWith("SAMPLE-LIVE-")&&points(a.code()).stream().allMatch(DemoWorkspaceService::localSynthetic)) {
                for(var p:points(a.code()))if(localSynthetic(p)) {
                    double upper=p.upperLimit()==null?100:p.upperLimit();
                    quality.ingest(p.code(),new PointSampleCommand(Math.round(upper*(.45+.1*Math.sin(old.ticks()*.2))*100)/100d,now,now));
                }
                var q=quality.summary(a.code());Integer score=q.healthAssessmentAllowed()?health.run(a.code()).healthScore():null;
                store.save("device-snapshot",a.code(),DeviceSnapshot.initial(a.code(),"运行",score,"虚拟规则评估",score==null?"limited":"good",
                    q.availabilityPercent(),"虚拟运行","独立演示区周期采样",null,null,null,null,now));
            }
            reminders.scan(a.code(),ACTOR); // Persistent cycle key prevents duplicate reminders.
        }
        store.save("demo-workspace","activity",new RunState(old.ticks()+1,now,"RUNNING","",true));
    }
}
