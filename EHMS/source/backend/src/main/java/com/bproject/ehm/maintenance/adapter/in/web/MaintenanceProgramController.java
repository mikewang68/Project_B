package com.bproject.ehm.maintenance.adapter.in.web;

import com.bproject.ehm.maintenance.application.InspectionSubmissionResult;
import com.bproject.ehm.maintenance.application.MaintenanceProgramApplicationService;
import com.bproject.ehm.maintenance.domain.model.DefectRecord;
import com.bproject.ehm.maintenance.domain.model.InspectionTask;
import com.bproject.ehm.maintenance.domain.model.InspectionTemplateItem;
import com.bproject.ehm.maintenance.domain.model.MaintenancePlan;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1")
public class MaintenanceProgramController {
    private final MaintenanceProgramApplicationService maintenance;

    public MaintenanceProgramController(MaintenanceProgramApplicationService maintenance) {
        this.maintenance = maintenance;
    }

    @GetMapping("/maintenance-plans")
    public List<MaintenancePlan> plans(@RequestParam String assetCode) {
        return maintenance.listPlans(assetCode);
    }

    @PostMapping("/maintenance-plans")
    @ResponseStatus(HttpStatus.CREATED)
    public MaintenancePlan createPlan(@Valid @RequestBody PlanRequest request) {
        List<InspectionTemplateItem> checklist = request.checklist().stream()
                .map(item -> new InspectionTemplateItem(item.itemId(), item.name(), item.method(),
                        item.standard(), item.unit(), item.required())).toList();
        return maintenance.createPlan(request.assetCode(), request.componentCode(), request.name(),
                request.strategyType(), request.cycleDays(), request.triggerCondition(), checklist,
                request.ownerTeam(), request.nextDueAt());
    }

    @PostMapping("/maintenance-plans/{planId}/inspection-tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public InspectionTask generate(@PathVariable String planId,
                                   @RequestBody(required = false) GenerateTaskRequest request) {
        GenerateTaskRequest value = request == null ? new GenerateTaskRequest(null, null) : request;
        return maintenance.generateTask(planId, value.scheduledAt(), value.assignee());
    }

    @GetMapping("/inspection-tasks")
    public List<InspectionTask> tasks(@RequestParam String assetCode) {
        return maintenance.listTasks(assetCode);
    }

    @GetMapping("/inspection-tasks/{taskNo}")
    public InspectionTask task(@PathVariable String taskNo) {
        return maintenance.getTask(taskNo);
    }

    @PatchMapping("/inspection-tasks/{taskNo}/start")
    public InspectionTask start(@PathVariable String taskNo,
                                @RequestBody(required = false) OperatorRequest request) {
        return maintenance.startTask(taskNo, request == null ? null : request.operator());
    }

    @PostMapping("/inspection-tasks/{taskNo}/submit")
    public InspectionSubmissionResult submit(@PathVariable String taskNo,
                                             @Valid @RequestBody SubmitTaskRequest request) {
        List<InspectionTask.ItemSubmission> items = request.items().stream()
                .map(item -> new InspectionTask.ItemSubmission(item.itemId(), item.measuredValue(),
                        item.result(), item.remark())).toList();
        return maintenance.submitTask(taskNo, items, request.conclusion(), request.operator(),
                request.defectSeverity(), request.defectDescription());
    }

    @GetMapping("/maintenance-defects")
    public List<DefectRecord> defects(@RequestParam String assetCode) {
        return maintenance.listDefects(assetCode);
    }

    @PostMapping("/maintenance-defects/{defectNo}/work-order")
    public DefectRecord createWorkOrder(@PathVariable String defectNo,
                                        @RequestBody(required = false) DefectWorkOrderRequest request) {
        DefectWorkOrderRequest value = request == null
                ? new DefectWorkOrderRequest(null, null, null, null) : request;
        return maintenance.createDefectWorkOrder(defectNo, value.title(), value.assignee(),
                value.plannedWindow(), value.operator());
    }

    public record PlanRequest(
            @NotBlank(message = "设备编码不能为空") String assetCode,
            String componentCode,
            @NotBlank(message = "计划名称不能为空") String name,
            String strategyType,
            @Min(value = 1, message = "周期不能小于1天")
            @Max(value = 3650, message = "周期不能大于3650天") int cycleDays,
            String triggerCondition,
            @NotEmpty(message = "至少需要一个点检项") List<@Valid ChecklistItemRequest> checklist,
            String ownerTeam,
            Instant nextDueAt
    ) {
    }

    public record ChecklistItemRequest(
            @NotBlank(message = "检查项编号不能为空") String itemId,
            @NotBlank(message = "检查项名称不能为空") String name,
            String method,
            @NotBlank(message = "检查标准不能为空") String standard,
            String unit,
            boolean required
    ) {
    }

    public record GenerateTaskRequest(Instant scheduledAt, String assignee) {
    }

    public record OperatorRequest(String operator) {
    }

    public record SubmitTaskRequest(
            @NotEmpty(message = "点检结果不能为空") List<@Valid ItemResultRequest> items,
            @NotBlank(message = "点检结论不能为空") String conclusion,
            String operator,
            String defectSeverity,
            String defectDescription
    ) {
    }

    public record ItemResultRequest(
            @NotBlank(message = "检查项编号不能为空") String itemId,
            String measuredValue,
            @NotBlank(message = "检查结果不能为空") String result,
            String remark
    ) {
    }

    public record DefectWorkOrderRequest(String title, String assignee, String plannedWindow, String operator) {
    }
}
