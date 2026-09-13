package com.bproject.ehm.health.adapter.in.web;

import com.bproject.ehm.health.application.HealthAssessmentApplicationService;
import com.bproject.ehm.health.domain.model.HealthAssessment;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1")
public class HealthAssessmentController {
    private final HealthAssessmentApplicationService health;

    public HealthAssessmentController(HealthAssessmentApplicationService health) {
        this.health = health;
    }

    @PostMapping("/devices/{assetCode}/health-assessments/run")
    @ResponseStatus(HttpStatus.CREATED)
    public HealthAssessment run(@PathVariable String assetCode) {
        return health.run(assetCode);
    }

    @GetMapping("/devices/{assetCode}/health-assessments/latest")
    public HealthAssessment latest(@PathVariable String assetCode) {
        return health.latest(assetCode);
    }

    @GetMapping("/devices/{assetCode}/health-assessments")
    public List<HealthAssessment> history(@PathVariable String assetCode,
                                         @RequestParam(defaultValue = "20") int limit) {
        return health.history(assetCode, limit);
    }

    @PostMapping("/health-assessments/{assessmentId}/review")
    public HealthAssessment review(@PathVariable String assessmentId,
                                   @Valid @RequestBody ReviewRequest request) {
        return health.review(assessmentId, request.decision(), request.comment(), request.reviewer());
    }

    @PostMapping("/health-assessments/{assessmentId}/work-order")
    public HealthAssessment createWorkOrder(@PathVariable String assessmentId,
                                            @RequestBody(required = false) WorkOrderRequest request) {
        WorkOrderRequest value = request == null ? new WorkOrderRequest(null, null, null, null) : request;
        return health.createWorkOrder(assessmentId, value.title(), value.assignee(),
                value.plannedWindow(), value.operator());
    }

    public record ReviewRequest(
            @NotBlank(message = "审核决定不能为空") String decision,
            @NotBlank(message = "审核意见不能为空") String comment,
            String reviewer
    ) {
    }

    public record WorkOrderRequest(String title, String assignee, String plannedWindow, String operator) {
    }
}
