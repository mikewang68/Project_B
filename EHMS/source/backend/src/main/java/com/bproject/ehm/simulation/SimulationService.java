package com.bproject.ehm.simulation;

import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.alarm.domain.model.*;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.caseflow.domain.model.*;
import com.bproject.ehm.workbench.domain.model.*;
import com.bproject.ehm.spares.domain.model.*;
import com.bproject.ehm.reliability.domain.model.*;
import java.math.BigDecimal;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.application.*;
import com.bproject.ehm.health.application.HealthAssessmentApplicationService;
import com.bproject.ehm.platform.audit.application.EhmAuditRelay;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.ProjectContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.*;
import java.util.*;
import java.util.function.Supplier;

/** Opt-in scenario orchestrator. Uses existing domain rules, never controls physical equipment. */
@Service @Profile("server")
@ConditionalOnProperty(name="ehm.simulation.enabled",havingValue="true")
public class SimulationService {
    public static final String ACTOR="[虚拟]运行模拟器",CONFIRMATION="SYNTHETIC_ONLY";
    private final OpenGaussJsonStore store;
    private final TransactionTemplate tx;
    private final DataQualityApplicationService quality;
    private final HealthAssessmentApplicationService health;
    private final EhmAuditRelay audit;
    private final String project,defaultProject;
    private final SimulationCatalog catalog;
    private final boolean shared;
    private final boolean auditRemote;
    public record Failure(String code,Instant at){}
    public record FaultSample(Map<String,Double> values,Instant at){}

    public record Settings(int intervalSeconds,double hoursPerTick,double cycleHours,double warningRatio,boolean autoWorkflow){
        public Settings{
            if(intervalSeconds<5||intervalSeconds>86400||!Double.isFinite(hoursPerTick)||hoursPerTick<=0||hoursPerTick>24
                    ||!Double.isFinite(cycleHours)||cycleHours<1||cycleHours>100000
                    ||!Double.isFinite(warningRatio)||warningRatio<.5||warningRatio>=1)
                throw new IllegalArgumentException("间隔5～86400秒；每轮推进大于0且不超过24小时；周期1～100000小时；阈值50%～小于100%");
        }
        public static Settings daily(){return new Settings(300,300.0/3600,40,.8,false);}
        public static Settings demo(){return new Settings(5,1,40,.8,true);}
    }
    public record Control(boolean initialized,boolean running,String runId,long ticks,double simulatedHours,
            Settings settings,Instant lastTickAt,Instant nextTickAt){}
    public record Progress(String assetCode,double totalHours,double serviceHours,int cycle,String alarmNo,
            String orderNo,String taskNo,int stage,String condition,Instant updatedAt){}
    public record Round(long tick,double simulatedHours,int assetsUpdated,int samplesWritten,int alarmsCreated,
            int workflowTransitions,int healthAssessments,int qualityBlocked,int skippedAssets,Instant occurredAt,boolean synthetic){}

    public SimulationService(OpenGaussJsonStore store,TransactionTemplate tx,DataQualityApplicationService quality,
            HealthAssessmentApplicationService health,EhmAuditRelay audit,ObjectMapper mapper,
            @Value("${ehm.simulation.project-id:SIM_EHM}") String project,
            @Value("${ehm.project.default-id:UNASSIGNED}") String defaultProject,
            @Value("${ehm.sample-data.enabled:false}") boolean samples,
            @Value("${ehm.telemetry.adapter:openGemini}") String adapter,
            @Value("${ehm.audit.endpoint:}") String auditEndpoint,
            @Value("${ehm.ai.enabled:false}") boolean ai,
            @Value("${ehm.simulation.mode:isolated}") String mode,
            @Value("${ehm.simulation.shared-data-approved:false}") boolean approved,
            @Value("${ehm.persistence.adapter:openGauss}") String persistence){
        this.store=store;this.tx=tx;this.quality=quality;this.health=health;this.audit=audit;
        this.project=ProjectContext.validate(project);this.defaultProject=defaultProject;this.catalog=SimulationCatalog.load(mapper);
        this.shared=mode.equals("shared-synthetic");
        this.auditRemote=shared&&!auditEndpoint.isBlank();
        if(shared){
            if(!approved||!project.equals(defaultProject)||project.equals("UNASSIGNED")||!adapter.equals("openGemini")||!persistence.equals("openGauss"))
                throw new IllegalStateException("服务器虚拟运行必须显式批准、绑定已有默认项目并保留openGauss/openGemini；不更改认证配置");
        }else if(!mode.equals("isolated")||!project.startsWith("SIM_")||!samples||!adapter.equals("sample")||!auditEndpoint.isBlank()||ai)
            throw new IllegalStateException("模拟运行仅允许SIM_隔离项目、sample时序、禁用外部日志投递与AI；拒绝连接现场/公共链");
    }
    @PostConstruct void initializeController(){
        scoped(()->{tx.executeWithoutResult(s->store.insertIfAbsent("simulation-control","control",
                new Control(false,false,UUID.randomUUID().toString(),0,0,Settings.daily(),null,null)));return null;});
    }
    private <T>T scoped(Supplier<T> body){
        try(var ignored=ProjectContext.bind(project)){return body.get();}
        catch(RuntimeException e){throw e;}catch(Exception e){throw new IllegalStateException(e);}
    }
    public boolean currentProjectAllowed(){return project.equals(ProjectContext.require(defaultProject));}
    private Control control(){return store.find("simulation-control","control",Control.class).orElseThrow();}
    private <T>T locked(Supplier<T> body){return scoped(()->tx.execute(s->{store.lockSimulationControl();return body.get();}));}
    public Map<String,Object> status(){return scoped(()->{
        var c=control();var result=new LinkedHashMap<String,Object>();
        result.put("enabled",true);result.put("synthetic",true);result.put("projectId",project);result.put("control",c);
        result.put("catalog",catalog);result.put("devices",store.findAll("simulation-progress",Progress.class));
        result.put("latestRound",store.find("simulation-round","latest",Round.class).orElse(null));
        var failure=store.find("simulation-failure","latest",Failure.class).orElse(null);
        result.put("lastFailure",failure==null?"":failure.code());result.put("failedAt",failure==null?null:failure.at());
        result.put("mode",shared?"shared-synthetic":"isolated");
        result.put("externalDelivery",auditRemote);result.put("auditPolicy",shared?"EXISTING_SERVER_AUDIT_POLICY_UNCHANGED":"LOCAL_ONLY");
        result.put("notifications","IN_APP_TASKS_ONLY");
        result.put("message","仅虚拟运行；时间戳为实际写入时间，累计工时按模拟时钟推进；维保周期不等于设计寿命或AI预测");
        return result;
    });}
    public Map<String,Object> install(String confirmation){
        confirm(confirmation);
        locked(()->{
            var c=control();if(c.initialized()){supportFixtures(Instant.now());return null;}
            if(!shared&&store.count("asset")!=0)throw new IllegalStateException("模拟项目已有非本运行器资产，请使用空的隔离项目；不会覆盖已有数据");
            if(shared&&store.findAll("asset",Asset.class).stream().anyMatch(a->a.code().startsWith("SAMPLE-LIVE-")))
                throw new IllegalStateException("已有SAMPLE-LIVE标识但无完整模拟状态，拒绝覆盖；请核查来源");
            Instant now=Instant.now();int index=0;
            for(var entry:catalog.entries()){
                String code=entry.assetCode(),name="[虚拟]"+entry.name(),component=code+"-CORE";
                write("asset",code,Asset.create(code,name,entry.name(),"虚拟货场 / "+entry.profile(),
                        LocalDate.now(ZoneId.of("Asia/Shanghai")).plusDays(1).toString(),"虚拟运维班",now),"CREATE","建立范围映射设备 "+entry.scopeCode());
                write("component",component,Component.create(component,code,null,"[虚拟]健康监测对象","虚拟部件", "未选型","未选型","SIMULATED","演示", "虚拟位置",LocalDate.now().toString(),"在役",now),"CREATE","建立虚拟部件");
                for(var metric:SimulationCatalog.metrics(entry.profile())){
                    String point=code+"-"+metric.code();
                    write("measurement-point",point,MeasurementPoint.create(point,code,component,"[虚拟]"+metric.name(),metric.name(),metric.unit(),
                            "SYNTHETIC","sample://"+point,c.settings().intervalSeconds(),0.0,metric.upper(),true,now),"CREATE","建立虚拟测点；量程仅为演示假设");
                }
                var items=List.of(new InspectionTemplateItem("STATE","状态与外观","模拟检查","演示判据","—",true));
                write("maintenance-plan",code+"-PLAN",MaintenancePlan.create(code+"-PLAN",code,name,component,"[虚拟]每日点检","PERIODIC",1,
                        "由模拟时钟每日生成",items,"虚拟运维班",now.plusSeconds(86400),now),"CREATE","建立虚拟点检计划");
                double initial=index++==0?c.settings().cycleHours()*c.settings().warningRatio()-.5:(index*3)%24;
                store.save("simulation-progress",code,new Progress(code,initial,initial,1,null,null,null,0,"待采样",now));
                store.save("device-snapshot",code,DeviceSnapshot.initial(code,"待接入",null,"待评估","limited",0.0,"虚拟运行","尚未生成采样",null,null,null,null,now));
            }
            for(String profile:List.of("ROTATING","WEIGHING","PROCESS","ELECTRONIC")){
                var points=SimulationCatalog.metrics(profile).stream().map(m->new DeviceTemplate.TemplatePoint(m.code(),m.name(),m.name(),m.unit(),1,0.0,m.upper(),true)).toList();
                write("device-template","SAMPLE-LIVE-TPL-"+profile,DeviceTemplate.create("SAMPLE-LIVE-TPL-"+profile,"[虚拟]"+profile+"监测模板",profile,
                        List.of(new DeviceTemplate.TemplateComponent("CORE","监测对象","虚拟部件",1,true)),points,"虚拟规则维保","演示",ACTOR,now),"CREATE","建立草稿模板；未冒充现场批准");
            }
            supportFixtures(now);
            store.save("simulation-control","control",new Control(true,false,c.runId(),0,0,c.settings(),null,null));
            return null;
        });return status();
    }
    public Map<String,Object> configure(Settings settings,String confirmation){
        confirm(confirmation);Objects.requireNonNull(settings,"缺少参数");locked(()->{var c=control();
            if(c.running())throw new IllegalStateException("请先暂停再调整模拟参数");
            if(c.initialized()&&settings.intervalSeconds()!=c.settings().intervalSeconds()){
                for(var point:store.findAll("measurement-point",MeasurementPoint.class)){
                    if(point.code().startsWith("SAMPLE-LIVE-")&&!point.archived()&&point.sourceProtocol().equals("SYNTHETIC")
                            &&point.sourceAddress().equals("sample://"+point.code()))
                        store.save("measurement-point",point.code(),point.update(point.componentCode(),point.name(),point.metric(),point.unit(),
                                point.sourceProtocol(),point.sourceAddress(),settings.intervalSeconds(),point.lowerLimit(),point.upperLimit(),point.enabled(),Instant.now()));
                }
            }
            store.save("simulation-control","control",new Control(c.initialized(),false,c.runId(),c.ticks(),c.simulatedHours(),settings,c.lastTickAt(),null));
            audit.simulationEvent("SIM-"+UUID.randomUUID(),"UPDATE","simulation-settings","调整模拟器及其虚拟测点采样周期，不改变真实设备或PLC参数");return null;
        });return status();
    }
    public Map<String,Object> running(boolean running,String confirmation){
        confirm(confirmation);locked(()->{var c=control();if(!c.initialized())throw new IllegalStateException("请先初始化设备范围场景");
            store.save("simulation-control","control",new Control(true,running,c.runId(),c.ticks(),c.simulatedHours(),c.settings(),c.lastTickAt(),running?Instant.now():null));
            audit.simulationEvent("SIM-"+UUID.randomUUID(),"EXECUTE","simulation-control",running?"启动持续模拟运行":"暂停模拟运行，已有记录保留");return null;
        });return status();
    }
    public Map<String,Object> tick(String requestId,String confirmation){
        confirm(confirmation);if(requestId==null||!requestId.matches("[A-Za-z0-9_-]{8,80}"))throw new IllegalArgumentException("必须提供8～80位幂等请求号");
        return execute(requestId,false);
    }
    public void scheduledTick(){
        var c=scoped(this::control);if(!c.running()||c.nextTickAt()==null||Instant.now().isBefore(c.nextTickAt()))return;
        execute("scheduled-"+c.nextTickAt().toEpochMilli(),true);
    }
    private Map<String,Object> execute(String requestId,boolean scheduled){
        try{return locked(()->{
            var old=store.find("simulation-receipt",requestId,Round.class);
            if(old.isPresent())return Map.of("replayed",true,"round",old.get());
            var c=control();
            if(!c.initialized())throw new IllegalStateException("请先初始化场景");
            if(c.ticks()>=8640)throw new IllegalStateException("本地演练已达8640轮容量保护上限，请导出验收资料后人工安排新场景");
            Instant now=Instant.now();
            if(scheduled&&(!c.running()||c.nextTickAt()==null||now.isBefore(c.nextTickAt())))return Map.of("skipped",true);
            Round round=advance(c,now);
            store.save("simulation-receipt",requestId,round);store.save("simulation-round","latest",round);
            boolean keepRunning=c.running()&&!(c.settings().intervalSeconds()<60&&round.tick()%360==0);
            store.save("simulation-control","control",new Control(true,keepRunning,c.runId(),round.tick(),round.simulatedHours(),c.settings(),now,keepRunning?now.plusSeconds(c.settings().intervalSeconds()):null));
            store.save("simulation-failure","latest",new Failure("",null));return Map.of("replayed",false,"round",round);
        });}catch(RuntimeException e){
            // Do not hammer a broken dependency; state changes above rolled back as one transaction.
            try{locked(()->{var c=control();store.save("simulation-control","control",new Control(c.initialized(),false,c.runId(),c.ticks(),c.simulatedHours(),c.settings(),c.lastTickAt(),null));
                store.save("simulation-failure","latest",new Failure("ROUND_ROLLED_BACK: "+e.getClass().getSimpleName()+"；请检查本地服务日志后手动重试",Instant.now()));return null;});}catch(Exception ignored){}
            throw e;
        }
    }
    private Round advance(Control control,Instant now){
        long tick=control.ticks()+1;double elapsed=control.simulatedHours()+control.settings().hoursPerTick();
        int updated=0,samples=0,alarms=0,transitions=0,assessments=0,blocked=0,skipped=0,index=0;
        for(var entry:catalog.entries()){
            String code=entry.assetCode();var asset=store.find("asset",code,Asset.class).orElseThrow();
            if(asset.archived()){skipped++;continue;}
            var p=store.find("simulation-progress",code,Progress.class).orElseThrow();
            // Periodic, visible outage on one electronic asset; no health score fabricated during outage.
            boolean outage=entry.scopeCode().equals("EQ-IMG-17")&&tick%24>=12&&tick%24<=14;
            // No unverified running hours are added while offline or under simulated maintenance.
            double increment=outage||p.stage()>=2?0:control.settings().hoursPerTick();
            double hours=p.totalHours()+increment,service=p.serviceHours()+increment;
            double ratio=service/control.settings().cycleHours();
            boolean restoring=p.stage()==4;
            var metrics=SimulationCatalog.metrics(entry.profile());var values=new HashMap<String,Double>();
            boolean qualityReady=true;String reason="";
            for(var m:metrics){
                var point=store.find("measurement-point",code+"-"+m.code(),MeasurementPoint.class).orElseThrow();
                if(point.archived()||!point.enabled()){qualityReady=false;reason="测点已归档或停用";continue;}
                double value=Math.round(m.upper()*Math.min(.98,(restoring?.38:.36+Math.min(1,ratio)*.52)+.025*Math.sin(tick*.5+index++))*100)/100.0;
                values.put(m.code(),value);
                var result=quality.ingest(point.code(),new PointSampleCommand(value,outage?now.minusSeconds(Math.max(7200,point.sampleIntervalSeconds()*6L+60)):now,now));samples++;
                if(!result.qualityStatus().equals("GOOD")){qualityReady=false;reason=result.message();}
            }
            Integer score=null;
            if(qualityReady){var assessment=health.run(code);score=assessment.healthScore();assessments++;}
            else blocked++;
            var current=new Progress(code,hours,service,p.cycle(),p.alarmNo(),p.orderNo(),p.taskNo(),p.stage(),outage?"离线":p.stage()>=2?"检修":"运行",now);
            if(current.alarmNo()==null&&ratio>=control.settings().warningRatio()){
                current=openMaintenance(entry,current,control.settings(),now);alarms++;
                store.save("simulation-fault-sample",current.alarmNo(),new FaultSample(Map.copyOf(values),now));
            }else if(current.alarmNo()!=null&&control.settings().autoWorkflow()&&qualityReady){
                var advanced=progressWorkflow(entry,current,values,now);
                if(advanced.stage()!=current.stage()||advanced.cycle()!=current.cycle())transitions++;
                current=advanced;
            }
            if(outage){
                String id="SAMPLE-LIVE-DATA-"+(tick/24)+"-"+entry.scopeCode();
                if(store.find("alarm",id,Alarm.class).isEmpty()){
                    write("alarm",id,new Alarm(id,code,asset.name(),"虚拟通信链路","DATA 数据质量","limited","[虚拟]模拟断流，暂停健康评估",AlarmStatus.NEW,
                            "待核查","模拟器注入过期源时间",now,null,null,"虚拟通信班",List.of(),null),"CREATE","发现虚拟断流，不生成正常健康分");
                    write("user-task",id,UserTask.create(id,"数据质量核查","ALARM",id,"[虚拟]采集断流核查", "站内提醒；没有发送短信、邮件或设备指令",code,"虚拟通信班","虚拟通信班","P2 中",now.plusSeconds(3600),now),"CREATE","生成站内通信核查待办");
                    alarms++;
                }
            }else if(qualityReady&&entry.scopeCode().equals("EQ-IMG-17")){
                String id="SAMPLE-LIVE-DATA-"+(tick/24)+"-"+entry.scopeCode();
                var fault=store.find("alarm",id,Alarm.class).orElse(null);
                if(fault!=null&&fault.status()!=AlarmStatus.CLOSED){
                    if(fault.status()==AlarmStatus.NEW)fault=fault.acknowledge(ACTOR,now);
                    write("alarm",id,fault.close(ACTOR,"[虚拟]新一轮有效采样已恢复",now),"UPDATE","通信恢复，关闭虚拟数据质量告警");
                    var task=store.find("user-task",id,UserTask.class).orElseThrow();
                    if(!"CANCELLED".equals(task.status()))write("user-task",id,task.complete(ACTOR,now),"UPDATE","数据质量恢复，核查待办完成");
                }
            }
            store.save("simulation-progress",code,current);
            double available=quality.summary(code).availabilityPercent();
            store.save("device-snapshot",code,DeviceSnapshot.initial(code,current.condition(),score,
                    !qualityReady?"数据受限":current.alarmNo()!=null?"维保预警":"规则评估",!qualityReady?"limited":current.alarmNo()!=null?"warn":"good",
                    available,"虚拟运行",!qualityReady?reason:current.alarmNo()!=null?"演示周期达到阈值":"无周期预警",
                    values.get("TEMP"),values.get("VIB"),values.get("CURRENT"),null,now));
            audit.simulationEvent("SIM-"+UUID.randomUUID(),"UPDATE",code,"第"+tick+"轮：虚拟采样与规则评估，周期累计"+round(service)+"小时；不代表设计寿命");
            updated++;
            long day=(long)(elapsed/24);
            if(control.ticks()==0||day>(long)(control.simulatedHours()/24))dailyRecords(entry,day,now);
        }
        if(control.ticks()==0||(long)(elapsed/12)>(long)(control.simulatedHours()/12))handover((long)(elapsed/12),now);
        if(!shared&&tick%288==0)store.pruneSimulationSamples(now.minusSeconds(7*86400));
        return new Round(tick,round(elapsed),updated,samples,alarms,transitions,assessments,blocked,skipped,now,true);
    }
    private Progress openMaintenance(SimulationCatalog.Entry e,Progress p,Settings settings,Instant now){
        String base=e.assetCode()+"-C"+p.cycle(),alarm=base+"-ALM",task=base+"-TODO",order=base+"-WO";
        String description="[虚拟]累计"+round(p.serviceHours())+"小时 / 演示周期"+settings.cycleHours()+"小时，达到"+Math.round(settings.warningRatio()*100)+"%阈值；不是设计寿命预测";
        write("alarm",alarm,new Alarm(alarm,e.assetCode(),"[虚拟]"+e.name(),"虚拟维保对象","L2 警告","warn",description,AlarmStatus.NEW,
                "站内待办提醒","累计运行工时规则",now,null,null,"虚拟运维班",List.of(),null),"CREATE",description);
        write("user-task",task,UserTask.create(task,"周期维保","ALARM",alarm,"[虚拟]"+e.name()+"维保提醒",description,e.assetCode(),"虚拟运维班","虚拟运维班","P2 中",now.plusSeconds(3600),now),"CREATE","生成站内维保待办；不冒充消息已送达真实人员");
        write("work-order",order,WorkOrder.create(order,e.assetCode(),"[虚拟]"+e.name(),"[虚拟]周期维保核查","P2 中","虚拟运维班",alarm,
                description,"虚拟演练窗口",ACTOR,now),"CREATE","按虚拟规则提交工单，待审批");
        write("maintenance-case",alarm,MaintenanceCase.open(alarm,e.assetCode(),"[虚拟]"+e.name(),"虚拟维保对象","L2",description,
                List.of(new EvidenceItem("SIMULATION","虚拟累计工时",description,e.assetCode(),now)),now),"CREATE","建立虚拟告警处置档案");
        return new Progress(p.assetCode(),p.totalHours(),p.serviceHours(),p.cycle(),alarm,order,task,1,"运行",now);
    }
    private Progress progressWorkflow(SimulationCatalog.Entry e,Progress p,Map<String,Double> values,Instant now){
        var order=store.find("work-order",p.orderNo(),WorkOrder.class).orElseThrow();
        var alarm=store.find("alarm",p.alarmNo(),Alarm.class).orElseThrow();
        var flow=store.find("maintenance-case",p.alarmNo(),MaintenanceCase.class).orElseThrow();
        // Human intervention owns the flow: do not auto-override a different state or a manual closure.
        var expected=switch(p.stage()){case 1->WorkOrderStatus.SUBMITTED;case 2->WorkOrderStatus.APPROVED;case 3->WorkOrderStatus.IN_PROGRESS;default->WorkOrderStatus.WAITING_VERIFY;};
        if(order.status()!=expected||alarm.status()==AlarmStatus.CLOSED)return p;
        int next=p.stage()+1;
        if(p.stage()==1){
            alarm=alarm.acknowledge(ACTOR,now);order=order.transitionTo(WorkOrderStatus.APPROVED,ACTOR,"[虚拟]自动演练审批，非真实人员审批",now);
            flow=flow.diagnose("[虚拟]周期到期核查","虚拟累计工时达到阈值","规则演练","依据模拟周期计数，不属于现场诊断",ACTOR,now).linkWorkOrder(order.orderNo(),now);
        }else if(p.stage()==2){order=order.transitionTo(WorkOrderStatus.IN_PROGRESS,ACTOR,"[虚拟]模拟执行",now);
            boolean issued=issueSyntheticKit(e,p,now);
            flow=flow.recordExecution("[虚拟]模拟检查保养","演练执行记录","[虚拟]无实际设备，不下发控制",issued?"虚拟耗材包领用1套，参见库存流水":"虚拟库存不足，未领料；仅做检查",ACTOR,now);
        }else if(p.stage()==3){order=order.transitionTo(WorkOrderStatus.WAITING_VERIFY,ACTOR,"[虚拟]等待下一轮恢复采样复测",now);
        }else{
            var metric=SimulationCatalog.metrics(e.profile()).get(0);Double after=values.get(metric.code());
            if(after==null||after>=metric.upper()*.65)return p;
            var before=store.find("simulation-fault-sample",p.alarmNo(),FaultSample.class).orElseThrow();
            flow=flow.recordRetest(e.assetCode()+"-"+metric.code(),before.values().get(metric.code()),after,metric.unit(),"[虚拟]本轮有效采样 < 演示量程65%；前值取本周期告警时的采样",true,ACTOR,now).close(ACTOR,"[虚拟]复测通过，周期归零但总工时保留",now);
            order=order.transitionTo(WorkOrderStatus.CLOSED,ACTOR,"[虚拟]复测通过",now);alarm=alarm.close(ACTOR,"[虚拟]复测通过",now);
            var task=store.find("user-task",p.taskNo(),UserTask.class).orElseThrow();
            write("user-task",p.taskNo(),task.complete(ACTOR,now),"UPDATE","完成虚拟站内待办");
            write("knowledge-case",p.orderNo(),KnowledgeCase.create(p.orderNo(),"SAMPLE-LIVE-CYCLE",e.name(),"虚拟维保对象",
                    "[虚拟]"+e.name()+"周期维保演练", "虚拟累计工时达到提醒比例","虚拟周期触发，不代表故障根因已确认",
                    List.of("核对模拟周期计数","虚拟检查保养","核对本周期告警时采样与恢复后采样"),"虚拟周期检查完成",
                    "本周期复测采样低于演示量程65%",p.orderNo(),now),"CREATE","闭环形成待审核的虚拟知识案例，不伪造专家验证");
            next=0;
        }
        write("alarm",alarm.alarmNo(),alarm,"UPDATE","虚拟告警状态推进至"+alarm.status());
        write("work-order",order.orderNo(),order,"UPDATE","虚拟工单状态推进至"+order.status());
        write("maintenance-case",p.alarmNo(),flow,"UPDATE","虚拟诊断、执行与复测流程推进");
        return next==0?new Progress(p.assetCode(),p.totalHours(),0,p.cycle()+1,null,null,null,0,"运行",now)
                :new Progress(p.assetCode(),p.totalHours(),p.serviceHours(),p.cycle(),p.alarmNo(),p.orderNo(),p.taskNo(),next,"检修",now);
    }
    private void dailyRecords(SimulationCatalog.Entry e,long day,Instant now){
        String code=e.assetCode(),id=code+"-DAY"+day;var plan=store.find("maintenance-plan",code+"-PLAN",MaintenancePlan.class).orElseThrow();
        var task=InspectionTask.create(id,plan,now,"虚拟巡检班",now);
        write("inspection-task",id,task,"CREATE","虚拟第"+day+"运行日点检任务");
        if(day%7==0&&e.scopeCode().equals("EQ-IMG-01")){
            write("configuration-change",id,ConfigurationChange.create(id,code,"MEASUREMENT_POINT",code+"-TEMP","虚拟变更建议",
                    Map.of("sampleIntervalSeconds",store.find("measurement-point",code+"-TEMP",MeasurementPoint.class).orElseThrow().sampleIntervalSeconds()),Map.of("sampleIntervalSeconds",60),"[虚拟]维保预警后建议评估采样频率",
                    "仅待审批草稿，不实际修改设备、PLC或测点参数",ACTOR,null,now),"CREATE","新增虚拟参数变更草稿，等待人工核查");
        }
        // Leave tasks pending for the user to inspect and execute; do not fabricate inspection signatures.
        if(day%7==0){var metric=SimulationCatalog.metrics(e.profile()).get(0);
            write("calibration-record",id,CalibrationRecord.create(id,code,code+"-CORE",code+"-"+metric.code(),"SIMULATED-SENSOR", "虚拟校准演练",1.0,1.0,.1,metric.unit(),"PASS","SIMULATED-NO-CERT","虚拟计量室",ACTOR,now,now.plusSeconds(7*86400),null,"全部数值为虚拟；没有真实校准证书",now),"CREATE","新增虚拟校准记录；无真实证书");}
    }
    private void supportFixtures(Instant now){
        for(String profile:List.of("ROTATING","WEIGHING","PROCESS","ELECTRONIC")){
            String part="SAMPLE-LIVE-KIT-"+profile;
            if(store.find("spare-part",part,SparePart.class).isEmpty()){
                write("spare-part",part,SparePart.create(part,"[虚拟]"+profile+"核查耗材包","虚拟规格，非采购型号","虚拟耗材","套",
                        catalog.entries().stream().filter(e->e.profile().equals(profile)).map(SimulationCatalog.Entry::assetCode).toList(),2,3,1,BigDecimal.ZERO,now),"CREATE","建立虚拟耗材，不虚构采购价格");
                var balance=StockBalance.empty("SAMPLE-LIVE-WH",part,now).receive(6,now);
                write("stock-balance",balance.balanceId(),balance,"CREATE","虚拟期初库存6套");
                stockTransaction(part+"-INIT","RECEIPT",balance,null,null,6,"[虚拟]场景期初入库",now);
            }
            String id="SAMPLE-LIVE-FM-"+profile;
            if(store.find("failure-mode",id,Object.class).isEmpty())write("failure-mode",id,
                    FailureMode.create(id,"SAMPLE-LIVE-CYCLE",profile,"虚拟维保对象","[虚拟]周期维保延误风险","演示周期超过提醒阈值",
                            "虚拟工时累积",5,3,3,"累计工时与数据质量核对","检查并复测；所有评分仅为演示假设",ACTOR,now),"CREATE","建立明确假设的虚拟风险登记");
        }
    }
    private boolean issueSyntheticKit(SimulationCatalog.Entry e,Progress p,Instant now){
        String part="SAMPLE-LIVE-KIT-"+e.profile(),reservation=p.orderNo()+"-KIT";
        var balance=store.find("stock-balance","SAMPLE-LIVE-WH:"+part,StockBalance.class).orElseThrow();
        if(balance.availableQuantity()<1){
            String task=p.orderNo()+"-STOCK";
            write("user-task",task,UserTask.create(task,"虚拟缺料","WORK_ORDER",p.orderNo(),"[虚拟]耗材库存不足",
                    "请在虚拟仓库手动补货；模拟器不自动伪造采购或真实领料",e.assetCode(),"虚拟仓管班","虚拟仓管班","P2 中",now.plusSeconds(86400),now),"CREATE","虚拟库存不足触发站内提醒");
            return false;
        }
        var item=store.find("spare-part",part,SparePart.class).orElseThrow();
        var reserved=balance.reserve(1,now);
        var request=SpareReservation.create(reservation,p.orderNo(),e.assetCode(),"SAMPLE-LIVE-WH",item,1,"[虚拟]维保演练耗材",ACTOR,now);
        stockTransaction(reservation+"-RESERVE","RESERVE",reserved,p.orderNo(),reservation,1,"[虚拟]工单耗材预留",now);
        var issued=reserved.issue(1,now);
        write("spare-reservation",reservation,request.issue(1,now),"CREATE","虚拟耗材预留并领用");
        write("stock-balance",issued.balanceId(),issued,"UPDATE","虚拟库存扣减1套");
        stockTransaction(reservation+"-ISSUE","ISSUE",issued,p.orderNo(),reservation,1,"[虚拟]工单耗材领用",now);
        return true;
    }
    private void stockTransaction(String id,String type,StockBalance balance,String order,String reservation,double quantity,String reason,Instant now){
        write("stock-transaction",id,new StockTransaction(id,type,balance.warehouseCode(),balance.partCode(),order,reservation,quantity,
                balance.onHandQuantity(),balance.reservedQuantity(),reason,ACTOR,now),"CREATE",reason);
    }
    private void handover(long shift,Instant now){
        String id="SAMPLE-LIVE-SHIFT-"+shift;
        var risks=store.findAll("alarm",Alarm.class).stream().filter(a->a.status()!=AlarmStatus.CLOSED).limit(20).map(a->a.deviceCode()+" "+a.summary()).toList();
        var pending=store.findAll("work-order",WorkOrder.class).stream().filter(w->w.status()!=WorkOrderStatus.CLOSED&&w.status()!=WorkOrderStatus.CANCELLED).limit(20).map(w->w.orderNo()+" "+w.status().label()).toList();
        write("shift-handover",id,ShiftHandover.create(id,LocalDate.ofInstant(now,ZoneId.of("Asia/Shanghai")),"虚拟上一班","虚拟下一班","虚拟交班人","虚拟接班人",
                "[虚拟]第"+shift+"班：从实际已保存的告警与工单汇总",risks,pending,List.of(),"草稿未签收；模拟时钟每12小时生成",now),"CREATE","生成虚拟班组交接草稿");
    }
    private void write(String type,String id,Object value,String action,String message){
        if(action.equals("CREATE")&&store.find(type,id,Object.class).isPresent())throw new IllegalStateException("虚拟标识冲突，拒绝覆盖既有记录："+id);
        store.save(type,id,value);audit.simulationEvent("SIM-"+UUID.randomUUID(),action,id,message);
    }
    private static void confirm(String value){if(!CONFIRMATION.equals(value))throw new IllegalArgumentException("必须确认SYNTHETIC_ONLY");}
    private static double round(double n){return Math.round(n*1000)/1000.0;}
}
