package com.bproject.ehm.caseflow.application;
import com.bproject.ehm.caseflow.ports.MaintenanceCaseRepository;
import com.bproject.ehm.caseflow.domain.model.EvidenceItem;
import com.bproject.ehm.asset.ports.MeasurementPointRepository;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.shared.error.*;
import com.fasterxml.jackson.databind.*;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.time.*;
import java.util.*;
@Service @Profile("server")
public class CaseEvidenceService {
    private final OpenGaussJsonStore store;private final MaintenanceCaseRepository cases;
    private final MeasurementPointRepository points;private final TelemetrySeriesPort telemetry;private final ObjectMapper json;
    public CaseEvidenceService(OpenGaussJsonStore store,MaintenanceCaseRepository cases,MeasurementPointRepository points,TelemetrySeriesPort telemetry,ObjectMapper json){
        this.store=store;this.cases=cases;this.points=points;this.telemetry=telemetry;this.json=json;
    }
    public record Window(String evidenceId,String alarmNo,String deviceCode,String pointCode,Instant from,Instant to,
        String captureSemantics,String capturedBy,Instant capturedAt,int returnedRows,boolean possiblyTruncated,String sha256,JsonNode observations){}
    public Window get(String id){return store.find("case-evidence-window",id,Window.class).orElseThrow(()->new ResourceNotFoundException("证据窗口不存在或不在当前区域"));}
    @Transactional public Window capture(String alarmNo,String pointCode,Instant from,Instant to,String actor){
        var current=cases.findByAlarmNo(alarmNo).orElseThrow(()->new ResourceNotFoundException("请先建立告警证据档案"));
        if(from==null||to==null||!from.isBefore(to)||Duration.between(from,to).compareTo(Duration.ofDays(1))>0)
            throw new ValidationException("证据窗口须有明确起止时间，单次不超过24小时");
        var p=points.findByCode(pointCode).orElseThrow(()->new ValidationException("未登记的测点"));
        if(!p.assetCode().equals(current.deviceCode()))throw new ValidationException("不能把其他设备的趋势存为本告警证据");
        JsonNode samples=pointCode.startsWith("MQ-SIM-")?json.valueToTree(store.recent("committed-observation","pointCode",pointCode,"sourceTimestamp",from,to,2000,JsonNode.class)):
            json.valueToTree(telemetry.history(pointCode,from,to,2000));
        if(samples.isEmpty())throw new DomainConflictException("此窗口无已保存样本，未生成空证据或用当前值替代");
        var now=Instant.now();String id="EV-"+UUID.randomUUID();String hash;
        try{hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(json.writeValueAsBytes(samples)));}catch(Exception e){throw new IllegalStateException(e);}
        var window=new Window(id,alarmNo,current.deviceCode(),pointCode,from,to,"HISTORICAL_WINDOW_CAPTURED_NOW_NOT_EVENT_TIME_SNAPSHOT",actor,now,samples.size(),samples.size()>=2000,hash,samples);
        store.save("case-evidence-window",id,window);
        cases.save(current.appendEvidence(new EvidenceItem("HISTORY_WINDOW","历史窗口 · "+pointCode,
            from+" 至 "+to+"；"+samples.size()+"条；SHA256="+hash+"；采集人："+actor+"；为现在回查固化的历史，不宣称发生当时已冻结",id,now),now));
        return window;
    }
    @Transactional public CaseFlowView revise(String alarmNo,String diagnosisId,String conclusion,String cause,String confidence,String evidence,String reason,String actor){
        var current=cases.findByAlarmNo(alarmNo).orElseThrow(()->new ResourceNotFoundException("闭环档案不存在"));
        if(current.diagnoses().stream().noneMatch(d->d.diagnosisId().equals(diagnosisId)))throw new ValidationException("被修订诊断不属于本档案");
        if(reason==null||reason.isBlank())throw new ValidationException("修订必须填写原因，旧诊断不可覆盖");
        var now=Instant.now();var next=current.diagnose(conclusion,cause,confidence,evidence,actor,now);
        String newId=next.diagnoses().get(next.diagnoses().size()-1).diagnosisId();
        return CaseFlowView.from(cases.save(next.appendEvidence(new EvidenceItem("DIAGNOSIS_REVISION",diagnosisId+" → "+newId,reason.trim()+"；修订人："+actor,diagnosisId,now),now)));
    }
}
