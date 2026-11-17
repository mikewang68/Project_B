package com.bproject.ehm.caseflow.adapter.in.web;
import com.bproject.ehm.caseflow.application.*;
import com.bproject.ehm.platform.security.EhmOperationIdentity;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
@RestController @Profile("server") @RequestMapping("/api/ehm/v1")
public class CaseEvidenceController {
    private final CaseEvidenceService service;
    public CaseEvidenceController(CaseEvidenceService service){this.service=service;}
    @GetMapping("/case-evidence-windows/{id}") public CaseEvidenceService.Window get(@PathVariable String id){return service.get(id);}
    @PostMapping("/alarms/{no}/case/evidence-windows") public CaseEvidenceService.Window capture(@PathVariable String no,@RequestBody Capture value,HttpServletRequest http){return service.capture(no,value.pointCode(),value.from(),value.to(),EhmOperationIdentity.require(http));}
    @PostMapping("/alarms/{no}/case/diagnoses/{id}/revisions") public CaseFlowView revise(@PathVariable String no,@PathVariable String id,@RequestBody Revision v,HttpServletRequest http){return service.revise(no,id,v.conclusion(),v.probableCause(),v.confidence(),v.evidence(),v.reason(),EhmOperationIdentity.require(http));}
    public record Capture(String pointCode,Instant from,Instant to){}
    public record Revision(String conclusion,String probableCause,String confidence,String evidence,String reason){}
}
