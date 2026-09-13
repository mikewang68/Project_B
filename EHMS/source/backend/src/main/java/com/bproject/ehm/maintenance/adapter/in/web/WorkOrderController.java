package com.bproject.ehm.maintenance.adapter.in.web;

import com.bproject.ehm.maintenance.application.WorkOrderApplicationService;
import com.bproject.ehm.maintenance.application.WorkOrderCommand;
import com.bproject.ehm.maintenance.application.WorkOrderView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/api/ehm/v1/work-orders")
public class WorkOrderController {
    private final WorkOrderApplicationService workOrders;

    public WorkOrderController(WorkOrderApplicationService workOrders) {
        this.workOrders = workOrders;
    }

    @GetMapping
    public PageResult<WorkOrderView> list(@RequestParam(defaultValue = "0") int page,
                                          @RequestParam(defaultValue = "50") int size) {
        return workOrders.list(new PageQuery(page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrderView create(@Valid @RequestBody CreateRequest request) {
        return workOrders.create(new WorkOrderCommand(request.deviceCode(), request.title(), request.priority(),
                request.assignee(), request.source(), request.description(), request.plannedWindow(), request.operator()));
    }

    @PatchMapping("/{orderNo}/status")
    public WorkOrderView changeStatus(@PathVariable String orderNo, @Valid @RequestBody StatusRequest request) {
        return workOrders.transition(orderNo, request.status(), request.operator(), request.reason());
    }

    public record CreateRequest(
            @NotBlank(message = "设备编码不能为空") String deviceCode,
            @NotBlank(message = "工单主题不能为空") String title,
            String priority,
            String assignee,
            String source,
            String description,
            String plannedWindow,
            String operator
    ) {
    }

    public record StatusRequest(
            @NotBlank(message = "目标状态不能为空") String status,
            String operator,
            String reason
    ) {
    }
}
