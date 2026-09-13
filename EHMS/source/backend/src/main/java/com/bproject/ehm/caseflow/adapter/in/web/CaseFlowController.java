package com.bproject.ehm.caseflow.adapter.in.web;

import com.bproject.ehm.caseflow.application.CaseFlowApplicationService;
import com.bproject.ehm.caseflow.application.CaseFlowView;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/ehm/v1")
public class CaseFlowController {
    private final CaseFlowApplicationService cases;

    public CaseFlowController(CaseFlowApplicationService cases) {
        this.cases = cases;
    }

    @GetMapping("/alarms/{alarmNo}/case")
    public CaseFlowView getByAlarm(@PathVariable String alarmNo) {
        return cases.getByAlarm(alarmNo);
    }

    @PostMapping("/alarms/{alarmNo}/case")
    @ResponseStatus(HttpStatus.CREATED)
    public CaseFlowView open(@PathVariable String alarmNo) {
        return cases.openEvidenceCase(alarmNo);
    }

    @PostMapping("/alarms/{alarmNo}/case/diagnoses")
    public CaseFlowView diagnose(@PathVariable String alarmNo, @Valid @RequestBody DiagnosisRequest request) {
        return cases.diagnose(alarmNo, request.conclusion(), request.probableCause(), request.confidence(),
                request.evidence(), request.operator());
    }

    @PostMapping("/alarms/{alarmNo}/case/work-order")
    public CaseFlowView convert(@PathVariable String alarmNo, @RequestBody(required = false) WorkOrderRequest request) {
        WorkOrderRequest value = request == null ? new WorkOrderRequest(null, null, null, null, null, null) : request;
        return cases.convertToWorkOrder(alarmNo, value.title(), value.priority(), value.assignee(),
                value.description(), value.plannedWindow(), value.operator());
    }

    @GetMapping("/work-orders/{orderNo}/case")
    public CaseFlowView getByWorkOrder(@PathVariable String orderNo) {
        return cases.getByWorkOrder(orderNo);
    }

    @PostMapping("/work-orders/{orderNo}/case/execution-records")
    public CaseFlowView recordExecution(@PathVariable String orderNo,
                                        @Valid @RequestBody ExecutionRequest request) {
        return cases.recordExecution(orderNo, request.action(), request.result(), request.safetyConfirmation(),
                request.partsUsed(), request.operator());
    }

    @PostMapping("/work-orders/{orderNo}/case/retests")
    public CaseFlowView recordRetest(@PathVariable String orderNo, @Valid @RequestBody RetestRequest request) {
        return cases.recordRetest(orderNo, request.pointCode(), request.beforeValue(), request.afterValue(),
                request.unit(), request.criterion(), request.passed(), request.operator());
    }

    @PostMapping("/work-orders/{orderNo}/case/close")
    public CaseFlowView close(@PathVariable String orderNo, @Valid @RequestBody CloseRequest request) {
        return cases.closeLoop(orderNo, request.conclusion(), request.operator());
    }

    public record DiagnosisRequest(
            @NotBlank(message = "诊断结论不能为空") String conclusion,
            @NotBlank(message = "可能原因不能为空") String probableCause,
            String confidence,
            @NotBlank(message = "诊断依据不能为空") String evidence,
            String operator
    ) {
    }

    public record WorkOrderRequest(
            String title,
            String priority,
            String assignee,
            String description,
            String plannedWindow,
            String operator
    ) {
    }

    public record ExecutionRequest(
            @NotBlank(message = "执行内容不能为空") String action,
            @NotBlank(message = "执行结果不能为空") String result,
            @NotBlank(message = "安全确认不能为空") String safetyConfirmation,
            String partsUsed,
            String operator
    ) {
    }

    public record RetestRequest(
            @NotBlank(message = "复测测点不能为空") String pointCode,
            @NotNull(message = "维修前数值不能为空") Double beforeValue,
            @NotNull(message = "维修后数值不能为空") Double afterValue,
            String unit,
            @NotBlank(message = "验收判据不能为空") String criterion,
            boolean passed,
            String operator
    ) {
    }

    public record CloseRequest(
            @NotBlank(message = "闭环结论不能为空") String conclusion,
            String operator
    ) {
    }
}
