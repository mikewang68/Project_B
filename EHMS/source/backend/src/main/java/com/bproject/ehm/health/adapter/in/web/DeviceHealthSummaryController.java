package com.bproject.ehm.health.adapter.in.web;

import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.health.ports.HealthAssessmentRepository;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

/** Read-only DTS contract. No online inference or synthetic healthy default on GET. */
@RestController @Profile("server") @RequestMapping("/api/ehm/v1")
public class DeviceHealthSummaryController {
    private final OpenGaussJsonStore store;
    private final AssetQueryFacade assets;
    private final HealthAssessmentRepository assessments;
    public DeviceHealthSummaryController(OpenGaussJsonStore store,AssetQueryFacade assets,HealthAssessmentRepository assessments){
        this.store=store;this.assets=assets;this.assessments=assessments;
    }
    @GetMapping("/devices/{code}/health-summary")
    public Summary get(@PathVariable String code){
        var device=assets.get(code);var tuple=store.readTuple("asset",device.code());
        var assessment=assessments.findRecentByAssetCode(device.code(),100).stream()
            .filter(a->a.method()!=null&&!a.method().contains("回放")).findFirst().orElse(null);
        var now=Instant.now();
        String status=assessment==null?"UNKNOWN":assessment.validUntil()==null||!assessment.validUntil().isAfter(now)?"STALE":"AVAILABLE";
        Boolean simulated=device.code().startsWith("MQ-SIM-")||device.code().startsWith("SAMPLE-LIVE-")?Boolean.TRUE:null;
        return new Summary("EHMS_DEVICE_HEALTH_SUMMARY_V1",tuple.projectId(),tuple.regionId(),tuple.subsystemCode(),
            device.code(),assessment==null?null:assessment.assessmentId(),status,
            "AVAILABLE".equals(status)?assessment.healthScore():null,"AVAILABLE".equals(status)?assessment.healthGrade():null,
            assessment==null?null:assessment.inputWindowEnd(),assessment==null?null:assessment.generatedAt(),
            assessment==null?null:assessment.validUntil(),assessment==null?null:assessment.dataAvailabilityPercent(),simulated,
            "AVAILABLE".equals(status)?null:assessment==null?"NO_CURRENT_ASSESSMENT":"ASSESSMENT_EXPIRED",
            assessment==null?null:assessment.method(),"/ehm/?page=asset-detail&asset="+device.code()+"&tab=prediction",now);
    }
    public record Summary(String schema,String projectId,String regionId,String subsystemCode,String deviceId,
        String assessmentId,String status,Integer score,String grade,Instant sourceTimestamp,Instant generatedAt,
        Instant validUntil,Double dataAvailabilityPercent,Boolean simulated,String unknownReason,String method,
        String detailUrl,Instant readAt){}
}
