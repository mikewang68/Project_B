package com.bproject.ehm.workbench.adapter.in.web;

import com.bproject.ehm.workbench.application.WorkbenchApplicationService;
import com.bproject.ehm.workbench.domain.model.ShiftHandover;
import com.bproject.ehm.workbench.domain.model.UserTask;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RestController
@RequestMapping("/api/ehm/v1")
public class WorkbenchController {
    private final WorkbenchApplicationService service;
    public WorkbenchController(WorkbenchApplicationService service) { this.service = service; }

    @GetMapping("/my-tasks")
    public List<UserTask> tasks(@RequestParam(required = false) String assignee,
                                @RequestParam(required = false) String status) {
        return service.listTasks(assignee, status);
    }
    @PostMapping("/my-tasks") @ResponseStatus(HttpStatus.CREATED)
    public UserTask createTask(@Valid @RequestBody TaskRequest request) {
        return service.createTask(request.taskType(), request.sourceType(), request.sourceId(), request.title(),
                request.description(), request.assetCode(), request.assignee(), request.team(), request.priority(), request.dueAt());
    }
    @PutMapping("/my-tasks/{taskNo}")
    public UserTask updateTask(@PathVariable String taskNo, @Valid @RequestBody TaskRequest request) {
        return service.updateTask(taskNo, request.title(), request.description(), request.assetCode(),
                request.assignee(), request.team(), request.priority(), request.dueAt(), request.status());
    }
    @PostMapping("/my-tasks/{taskNo}/complete")
    public UserTask completeTask(@PathVariable String taskNo, @RequestBody(required = false) OperatorRequest request) {
        return service.completeTask(taskNo, request == null ? null : request.operator());
    }

    @GetMapping("/shift-handovers")
    public List<ShiftHandover> handovers(@RequestParam(required = false) String status) {
        return service.listHandovers(status);
    }
    @PostMapping("/shift-handovers") @ResponseStatus(HttpStatus.CREATED)
    public ShiftHandover createHandover(@Valid @RequestBody HandoverRequest request) {
        return service.createHandover(request.shiftDate(), request.outgoingShift(), request.incomingShift(),
                request.outgoingLeader(), request.incomingLeader(), request.summary(), request.riskItems(),
                request.unfinishedItems(), request.equipmentExceptions(), request.notes());
    }
    @PostMapping("/shift-handovers/generate") @ResponseStatus(HttpStatus.CREATED)
    public ShiftHandover generate(@RequestBody(required = false) GenerateHandoverRequest request) {
        GenerateHandoverRequest value = request == null ? new GenerateHandoverRequest(null, null, null, null) : request;
        return service.generateHandover(value.outgoingShift(), value.incomingShift(), value.outgoingLeader(), value.incomingLeader());
    }
    @PutMapping("/shift-handovers/{handoverNo}")
    public ShiftHandover updateHandover(@PathVariable String handoverNo, @Valid @RequestBody HandoverRequest request) {
        return service.updateHandover(handoverNo, request.shiftDate(), request.outgoingShift(), request.incomingShift(),
                request.outgoingLeader(), request.incomingLeader(), request.summary(), request.riskItems(),
                request.unfinishedItems(), request.equipmentExceptions(), request.notes());
    }
    @PostMapping("/shift-handovers/{handoverNo}/submit")
    public ShiftHandover submit(@PathVariable String handoverNo, @RequestBody(required = false) OperatorRequest request) {
        return service.submitHandover(handoverNo, request == null ? null : request.operator());
    }
    @PostMapping("/shift-handovers/{handoverNo}/receive")
    public ShiftHandover receive(@PathVariable String handoverNo, @RequestBody(required = false) OperatorRequest request) {
        return service.receiveHandover(handoverNo, request == null ? null : request.operator());
    }

    public record TaskRequest(String taskType, String sourceType, String sourceId,
                              @NotBlank(message = "待办标题不能为空") String title,
                              String description, String assetCode, String assignee, String team,
                              String priority, Instant dueAt, String status) {}
    public record HandoverRequest(LocalDate shiftDate, String outgoingShift, String incomingShift,
                                  String outgoingLeader, String incomingLeader,
                                  @NotBlank(message = "交接摘要不能为空") String summary,
                                  List<String> riskItems, List<String> unfinishedItems,
                                  List<String> equipmentExceptions, String notes) {}
    public record GenerateHandoverRequest(String outgoingShift, String incomingShift,
                                          String outgoingLeader, String incomingLeader) {}
    public record OperatorRequest(String operator) {}
}
