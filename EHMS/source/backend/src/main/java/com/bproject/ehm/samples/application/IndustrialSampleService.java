package com.bproject.ehm.samples.application;

import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.alarm.domain.model.*;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.monitoring.domain.model.*;
import com.bproject.ehm.health.domain.model.*;
import com.bproject.ehm.reliability.domain.model.*;
import com.bproject.ehm.spares.domain.model.*;
import com.bproject.ehm.workbench.domain.model.*;
import com.bproject.ehm.caseflow.domain.model.*;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.math.BigDecimal;
import java.util.*;

/** Closed, insert-only fixture bundle, disabled in production. No arbitrary JSON/SQL upload API. */
@Service @Profile("server")
public class IndustrialSampleService {
    public static final String BUNDLE="industrial-sample-v1";
    private final OpenGaussJsonStore store;private final boolean enabled;private final String telemetryAdapter,storageAdapter;
    public IndustrialSampleService(OpenGaussJsonStore store,@Value("${ehm.sample-data.enabled:false}") boolean enabled,
            @Value("${ehm.telemetry.adapter:openGemini}") String telemetryAdapter,
            @Value("${ehm.persistence.adapter:openGauss}") String storageAdapter){
        this.store=store;this.enabled=enabled;this.telemetryAdapter=telemetryAdapter;this.storageAdapter=storageAdapter;
    }
    public Map<String,Object> status(){
        return Map.of("enabled",enabled,"bundle",BUNDLE,"synthetic",true,"telemetryAdapter",telemetryAdapter,
                "storageAdapter",storageAdapter,"imported",store.find("sample-bundle",BUNDLE,Map.class).isPresent(),
                "message","SAMPLE-前缀均为虚拟演示数据；不代表现场设备、AI预测或真实上链");
    }
    private void allowed(String confirmation){
        if(!enabled)throw new ResponseStatusException(HttpStatus.FORBIDDEN,"样例导入未启用；不会修改生产数据");
        if(!BUNDLE.equals(confirmation))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"请确认样例包版本");
    }
    private void put(Map<String,Integer> counts,String type,String id,Object value){
        if(store.insertIfAbsent(type,id,value))counts.merge(type,1,Integer::sum);
    }
    @Transactional public Map<String,Object> install(String confirmation){
        allowed(confirmation);
        // Anchor all deterministic fixture IDs/times to the first import. Re-runs can add newly
        // introduced child fixtures without resetting edits or accumulating duplicate history.
        Instant now=store.find("sample-bundle",BUNDLE,Map.class).map(m->Instant.parse(m.get("importedAt").toString())).orElseGet(Instant::now);
        LocalDate date=now.atOffset(ZoneOffset.ofHours(8)).toLocalDate();var counts=new LinkedHashMap<String,Integer>();
        String[] suffix={"GT-01","QC-02","FX-01","TS-03","UL-02","DF-01","GT-03","QC-04"};
        String[] names={"门式起重机","桥式起重机","翻箱机","提升机","卸料线","除尘系统","离线门式起重机","检修桥式起重机"};
        String[] types={"门吊","桥吊","翻箱机","提升机","卸料设备","除尘设备","门吊","桥吊"};
        String[] areas={"演示装卸一区","演示装卸二区","演示翻卸区","演示转运站","演示卸料区","演示环保区","演示装卸三区","演示检修库"};
        String[] teams={"演示机修二班","演示机修一班","演示液压班","演示机修三班","演示运输班","演示环保班","演示机修二班","演示电气班"};
        for(int i=0;i<suffix.length;i++){
            String code="SAMPLE-"+suffix[i],name="[虚拟]"+names[i],comp=code+"-DRIVE";Instant created=now.minus(25-i,ChronoUnit.DAYS);
            put(counts,"asset",code,Asset.create(code,name,types[i],areas[i],date.plusDays(i+2).toString(),teams[i],created));
            put(counts,"device-snapshot",code,DeviceSnapshot.initial(code,i==6?"离线":i==7?"检修":"运行",
                    i>=6?null:60+i*6,i>=6?"演示：数据受限":i<2?"演示：需核查":"演示：正常",i>=6?"limited":i<2?"warn":"good",
                    i>=6?0.0:100.0,"虚拟接入","虚拟场景，非现场告警",50.0+i,3.0+i*.4,80.0+i*5,null,now));
            put(counts,"component",comp,Component.create(comp,code,null,"[虚拟]驱动机构","传动部件","虚拟厂家","SAMPLE-MODEL","SYNTHETIC-SN-"+i,"A类","演示设备内部",date.minusYears(1).toString(),"在役",created));
            String[] metrics={"振动速度","温度","电流"},units={"mm/s","℃","A"},ends={"VIB","TEMP","CURRENT"};double[] upper={12,100,250};
            for(int p=0;p<3;p++){
                String point=code+"-"+ends[p];double base=p==0?4.0+i*.5:p==1?48+i*2:80+i*8;
                put(counts,"measurement-point",point,MeasurementPoint.create(point,code,comp,"[虚拟]"+metrics[p],metrics[p],units[p],"SYNTHETIC","sample://"+point,300,0.0,upper[p],true,created));
                Instant last=i==6?now.minus(2,ChronoUnit.HOURS):now;
                put(counts,"point-quality",point,PointQualitySnapshot.assess(point,code,true,300,0.0,upper[p],base,last,last,null,now));
                for(int s=0;s<=48;s++){
                    Instant at=last.minus((48-s)*30L,ChronoUnit.MINUTES);double value=Math.round((base*(.90+.1*s/48)+Math.sin(s*.7+p)*base*.025)*100)/100.0;
                    put(counts,"sample-telemetry",point+":"+at,new TelemetrySeriesPort.TelemetrySample(point,code,metrics[p],units[p],value,"SYNTHETIC_GOOD",at,at.plusMillis(80)));
                }
            }
            if(i<6){
                for(int day=12;day>=0;day--){Instant at=now.minus(day,ChronoUnit.DAYS);String id="SAMPLE-HA-"+suffix[i]+"-"+day;
                    double value=4+i*.5+(12-day)*.08;double utilization=value/12*100,deduction=Math.max(0,(utilization-50)*.8);int score=(int)Math.round(100-deduction);
                    var factor=new HealthFactor(code+"-VIB","振动速度",Math.round(value*100)/100.0,"mm/s",12.0,Math.round(utilization*10)/10.0,Math.round(deduction*10)/10.0,"虚拟历史输入的演示扣分；不等于现场模型结论");
                    put(counts,"health-assessment",id,HealthAssessment.completed(id,code,name,score,70,"虚拟样本历史演算（非AI寿命预测）","sample-threshold-v1","sample-feature-v1",100,List.of(factor),RulPrediction.unavailable("尚无经过验证的寿命模型，虚拟样例也不伪造RUL"),List.of("全部输入由程序生成，只供界面与流程验收","不是现场数据、预测精度证明或安全控制依据"),at.minusSeconds(300),at,at.plusSeconds(1500)));
                }
            }
            String alarm="SAMPLE-ALM-"+suffix[i];AlarmStatus alarmStatus=i==4?AlarmStatus.CLOSED:i==0?AlarmStatus.NEW:AlarmStatus.ACKNOWLEDGED;
            put(counts,"alarm",alarm,new Alarm(alarm,code,name,"虚拟驱动机构",i<2?"L3 严重":"L2 警告",i<2?"severe":"warn","[虚拟]振动变化需现场核查",alarmStatus,"演示SLA","虚拟规则场景",now.minus(i+1,ChronoUnit.HOURS),i==0?null:now.minus(i+1,ChronoUnit.MINUTES),i==4?now.minusSeconds(100):null,teams[i],List.of(),null));
            String order="SAMPLE-WO-"+suffix[i];WorkOrderStatus ws=new WorkOrderStatus[]{WorkOrderStatus.SUBMITTED,WorkOrderStatus.APPROVED,WorkOrderStatus.IN_PROGRESS,WorkOrderStatus.WAITING_VERIFY,WorkOrderStatus.CLOSED,WorkOrderStatus.DRAFT,WorkOrderStatus.SUBMITTED,WorkOrderStatus.APPROVED}[i];
            put(counts,"work-order",order,new WorkOrder(order,code,name,"[虚拟]"+names[i]+"专项检查","P2 中",ws,teams[i],"虚拟告警 "+alarm,"虚拟工单：先安全隔离，再检查、维修与复测",date.plusDays(1)+" 白班",created,now,List.of(),null));
            if(i==2||i==3||i==4){
                var flow=MaintenanceCase.open(alarm,code,name,"虚拟驱动机构","L2","虚拟告警处置",List.of(new EvidenceItem("SAMPLE","演示证据","虚拟采样值，不作为真实诊断依据",code+"-VIB",created)),created)
                    .diagnose("演示人工核查","演示润滑状态异常","仅演示","虚拟样本与虚拟现场记录","演示工程师",created).linkWorkOrder(order,created)
                    .recordExecution("演示润滑与紧固","演示维修完成","演示：已完成隔离挂牌","演示润滑油","演示维修员",now.minus(2,ChronoUnit.HOURS));
                if(i>=3)flow=flow.recordRetest(code+"-VIB",7.2,4.0,"mm/s","虚拟判据：低于5mm/s",true,"演示复测员",now.minus(1,ChronoUnit.HOURS));
                if(i==4)flow=flow.close("演示主管","虚拟闭环完成",now.minusSeconds(100));
                put(counts,"maintenance-case",alarm,flow);
            }else{
                put(counts,"maintenance-case",alarm,MaintenanceCase.open(alarm,code,name,"虚拟驱动机构",i<2?"L3":"L2","[虚拟]驱动状态核查",
                    List.of(new EvidenceItem("SAMPLE","虚拟采样快照","演示振动、温度、电流记录；不是现场证据",code+"-VIB",created)),created));
            }
            var checklist=List.of(new InspectionTemplateItem("VISUAL","外观及紧固","目视","无松动和渗漏","—",true),new InspectionTemplateItem("VIB","振动复测","仪表","虚拟标准：≤5mm/s","mm/s",true));
            String planId="SAMPLE-PLAN-"+suffix[i];var plan=MaintenancePlan.create(planId,code,name,comp,"[虚拟]周期点检","PERIODIC",7,"按演示周期",checklist,teams[i],now.plus(i+1,ChronoUnit.DAYS),created);
            put(counts,"maintenance-plan",planId,plan);String taskId="SAMPLE-INSP-"+suffix[i];var task=InspectionTask.create(taskId,plan,now.plus(1,ChronoUnit.DAYS),teams[i],created);
            if(i==1)task=task.start("演示点检员",now.minusSeconds(100));
            put(counts,"inspection-task",taskId,task);
            String no="SAMPLE-CAL-"+suffix[i];put(counts,"calibration-record",no,CalibrationRecord.create(no,code,comp,code+"-VIB","SAMPLE-SENSOR-"+i,"虚拟周期校准",4.3,4.1,.3,"mm/s",i==6?"LIMITED":"PASS","SAMPLE-CERT-"+i,"演示计量室","演示计量员",now.minus(300,ChronoUnit.DAYS),now.plus(i*10+2,ChronoUnit.DAYS),null,"虚拟证书编号，无真实附件",now));
            no="SAMPLE-CFG-"+suffix[i];var change=ConfigurationChange.create(no,code,"MEASUREMENT_POINT",code+"-VIB","虚拟采样周期变更",Map.of("intervalSeconds",300),Map.of("intervalSeconds",120),"演示变更申请","不写PLC，不改变真实设备参数","演示工程师",no+"-BEFORE",created);
            if(i==1)change=change.approve("演示主管",now);if(i==2)change=change.approve("演示主管",now).apply("演示执行人",now);
            put(counts,"configuration-change",no,change);
            String taskNo="SAMPLE-TASK-"+suffix[i];put(counts,"user-task",taskNo,UserTask.create(taskNo,"告警处置","ALARM",alarm,"[虚拟]"+names[i]+"告警核查","核查虚拟测点与工单，不用于现场控制",code,"演示工程师",teams[i],"P2 中",now.plus(i+1,ChronoUnit.HOURS),created));
        }
        for(int i=0;i<3;i++){
            String id="SAMPLE-TPL-"+i;var template=DeviceTemplate.create(id,"[虚拟]"+types[i]+"模板",types[i],List.of(new DeviceTemplate.TemplateComponent("DRIVE","驱动机构","传动部件",1,true)),List.of(new DeviceTemplate.TemplatePoint("VIB","振动速度","振动速度","mm/s",1,0.0,12.0,true)),"周期点检+状态核查","关键","演示工程师",now);
            if(i==1)template=template.publish("演示主管",now);put(counts,"device-template",id,template);
            id="SAMPLE-FM-"+i;put(counts,"failure-mode",id,FailureMode.create(id,"SAMPLE-FAULT-"+i,types[i],"虚拟驱动机构","[虚拟]轴承润滑异常","振动上升，需核查","虚拟润滑污染",8,4+i,3,"演示振动与温度监测","先核查传感器，再核查润滑","演示工程师",now));
            id="SAMPLE-RULE-"+i;var rule=AlarmRule.create(id,"[虚拟]振动趋势核查规则",types[i],"振动速度","value > 7.1","value < 5.0","L2",180,"演示工程师",now);if(i==1)rule=rule.publish("演示主管",now);put(counts,"alarm-rule",id,rule);
            id="SAMPLE-KB-"+i;var kb=KnowledgeCase.create(id,"SAMPLE-FAULT-"+i,types[i],"虚拟驱动机构","[虚拟]润滑异常处置案例","虚拟振动上升","虚拟润滑不足",List.of("核对测点","核对润滑","安全隔离后检查"),"演示补充润滑","同工况复测低于演示阈值","SAMPLE-WO-"+suffix[i],now);if(i==1)kb=kb.verify("演示主管",now);put(counts,"knowledge-case",id,kb);
            String part="SAMPLE-PART-"+i;put(counts,"spare-part",part,SparePart.create(part,"[虚拟]"+new String[]{"轴承","制动片","润滑油"}[i],"SAMPLE规格","演示备件","件",List.of("SAMPLE-"+suffix[i]),5,8,7,new BigDecimal("260.00"),now));
            var balance=StockBalance.empty("SAMPLE-WH",part,now).receive(i==1?3:20,now);put(counts,"stock-balance",balance.balanceId(),balance);
        }
        for(String level:List.of("L1","L2","L3","L4","DATA"))put(counts,"sla-policy",level,SlaPolicy.create(level,10,30,120,"演示值班主管",now));
        for(int i=0;i<3;i++){String id="SAMPLE-HO-"+i;var ho=ShiftHandover.create(id,date.minusDays(i),"白班","夜班","演示白班长","演示夜班长","[虚拟]请跟踪起重机告警与在途工单",List.of("SAMPLE-GT-01振动变化"),List.of("SAMPLE-WO-FX-01执行中"),List.of("SAMPLE-GT-03历史样本断流"),"演示交接，不代表真实签收",now.minus(i,ChronoUnit.DAYS));put(counts,"shift-handover",id,ho);}
        store.insertIfAbsent("sample-bundle",BUNDLE,Map.of("bundle",BUNDLE,"synthetic",true,"importedAt",now,"counts",counts));
        return Map.of("bundle",BUNDLE,"synthetic",true,"inserted",counts,"totalInserted",counts.values().stream().mapToInt(Integer::intValue).sum(),"message","仅补充不存在的记录，未覆盖既有数据；没有触发真实采集、AI预测或上链");
    }
    @Transactional public Map<String,Object> sampleNow(String confirmation){
        allowed(confirmation);Instant now=Instant.now();int count=0;
        for(var point:store.findAll("measurement-point",MeasurementPoint.class)){
            if(!point.code().startsWith("SAMPLE-")||point.archived()||!point.enabled()||point.assetCode().equals("SAMPLE-GT-03"))continue;
            double value=point.metric().equals("温度")?55:point.metric().equals("电流")?110:5.2;
            store.save("point-quality",point.code(),PointQualitySnapshot.assess(point.code(),point.assetCode(),true,point.sampleIntervalSeconds(),point.lowerLimit(),point.upperLimit(),value,now,now,null,now));
            var sample=new TelemetrySeriesPort.TelemetrySample(point.code(),point.assetCode(),point.metric(),point.unit(),value,"SYNTHETIC_GOOD",now,now);
            store.save("sample-telemetry",point.code()+":"+now,sample);count++;
        }
        return Map.of("samplesCreated",count,"synthetic",true,"sourceTimestamp",now,"message","生成一轮虚拟样本，仅写演示样本库和演示测点快照，不发送到现场或openGemini");
    }
}
