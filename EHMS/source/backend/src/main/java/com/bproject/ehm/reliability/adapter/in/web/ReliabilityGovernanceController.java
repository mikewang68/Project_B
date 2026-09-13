package com.bproject.ehm.reliability.adapter.in.web;

import com.bproject.ehm.reliability.application.ReliabilityGovernanceApplicationService;
import com.bproject.ehm.reliability.domain.model.AlarmRule;
import com.bproject.ehm.reliability.domain.model.FailureMode;
import com.bproject.ehm.reliability.domain.model.KnowledgeCase;
import com.bproject.ehm.reliability.domain.model.SlaPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1/reliability")
public class ReliabilityGovernanceController {
    private final ReliabilityGovernanceApplicationService governance;

    public ReliabilityGovernanceController(ReliabilityGovernanceApplicationService governance) {
        this.governance = governance;
    }

    @GetMapping("/failure-modes")
    public List<FailureMode> failureModes() { return governance.failureModes(); }

    @PostMapping("/failure-modes")
    @ResponseStatus(HttpStatus.CREATED)
    public FailureMode createFailureMode(@Valid @RequestBody FailureModeRequest request) {
        return governance.createFailureMode(request.faultCode(), request.assetType(), request.component(),
                request.failureName(), request.failureEffect(), request.failureCause(), request.severity(),
                request.occurrence(), request.detectability(), request.currentControl(),
                request.recommendedAction(), request.owner());
    }

    @PutMapping("/failure-modes/{id}")
    public FailureMode reviseFailureMode(@PathVariable String id,
                                         @Valid @RequestBody FailureModeRequest request) {
        return governance.reviseFailureMode(id, request.assetType(), request.component(),
                request.failureName(), request.failureEffect(), request.failureCause(), request.severity(),
                request.occurrence(), request.detectability(), request.currentControl(),
                request.recommendedAction(), request.owner());
    }

    @GetMapping("/fault-codes")
    public List<FailureMode> faultCodes() { return governance.failureModes(); }

    @GetMapping("/alarm-rules")
    public List<AlarmRule> alarmRules() { return governance.alarmRules(); }

    @PostMapping("/alarm-rules")
    @ResponseStatus(HttpStatus.CREATED)
    public AlarmRule createAlarmRule(@Valid @RequestBody AlarmRuleRequest request) {
        return governance.createAlarmRule(request.ruleCode(), request.name(), request.assetType(),
                request.metric(), request.conditionExpression(), request.recoveryExpression(),
                request.severity(), request.persistenceSeconds(), request.owner());
    }

    @PostMapping("/alarm-rules/{code}/publish")
    public AlarmRule publish(@PathVariable String code, @RequestBody(required = false) OperatorRequest request) {
        return governance.publishRule(code, request == null ? null : request.operator());
    }

    @PostMapping("/alarm-rules/{code}/disable")
    public AlarmRule disable(@PathVariable String code, @RequestBody(required = false) OperatorRequest request) {
        return governance.disableRule(code, request == null ? null : request.reason());
    }

    @GetMapping("/knowledge-cases")
    public List<KnowledgeCase> knowledgeCases() { return governance.knowledgeCases(); }

    @PostMapping("/knowledge-cases")
    @ResponseStatus(HttpStatus.CREATED)
    public KnowledgeCase createCase(@Valid @RequestBody KnowledgeCaseRequest request) {
        return governance.createKnowledgeCase(request.faultCode(), request.assetType(), request.component(),
                request.title(), request.symptom(), request.confirmedCause(), request.diagnosisSteps(),
                request.remedy(), request.verificationCriterion(), request.sourceWorkOrderNo());
    }

    @PostMapping("/knowledge-cases/{caseNo}/verify")
    public KnowledgeCase verifyCase(@PathVariable String caseNo,
                                    @RequestBody(required = false) OperatorRequest request) {
        return governance.verifyCase(caseNo, request == null ? null : request.operator());
    }

    @GetMapping("/sla-policies")
    public List<SlaPolicy> slaPolicies() { return governance.slaPolicies(); }

    @PutMapping("/sla-policies/{severity}")
    public SlaPolicy saveSla(@PathVariable String severity, @Valid @RequestBody SlaRequest request) {
        return governance.saveSla(severity, request.acknowledgeMinutes(), request.assignMinutes(),
                request.recoverMinutes(), request.escalationRole());
    }

    public record FailureModeRequest(
            @NotBlank String faultCode, @NotBlank String assetType, @NotBlank String component,
            @NotBlank String failureName, @NotBlank String failureEffect, @NotBlank String failureCause,
            @Min(1) @Max(10) int severity, @Min(1) @Max(10) int occurrence,
            @Min(1) @Max(10) int detectability, String currentControl,
            String recommendedAction, String owner) {}

    public record AlarmRuleRequest(
            @NotBlank String ruleCode, @NotBlank String name, @NotBlank String assetType,
            @NotBlank String metric, @NotBlank String conditionExpression,
            String recoveryExpression, @NotBlank String severity,
            @Min(0) @Max(86400) int persistenceSeconds, String owner) {}

    public record KnowledgeCaseRequest(
            @NotBlank String faultCode, @NotBlank String assetType, @NotBlank String component,
            @NotBlank String title, @NotBlank String symptom, @NotBlank String confirmedCause,
            @NotEmpty List<@NotBlank String> diagnosisSteps, @NotBlank String remedy,
            @NotBlank String verificationCriterion, String sourceWorkOrderNo) {}

    public record SlaRequest(@Min(1) int acknowledgeMinutes, @Min(1) int assignMinutes,
                             @Min(1) int recoverMinutes, @NotBlank String escalationRole) {}

    public record OperatorRequest(String operator, String reason) {}
}
