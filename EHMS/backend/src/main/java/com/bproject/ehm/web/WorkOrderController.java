package com.bproject.ehm.web;

import com.bproject.ehm.domain.WorkOrder;
import com.bproject.ehm.repository.DeviceRepository;
import com.bproject.ehm.repository.WorkOrderRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@RestController
@RequestMapping("/api/ehm/v1/work-orders")
public class WorkOrderController {
    private static final AtomicInteger SEQUENCE = new AtomicInteger(1);
    private static final DateTimeFormatter NUMBER_TIME = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
            .withZone(ZoneId.of("Asia/Shanghai"));

    private final WorkOrderRepository repository;
    private final DeviceRepository devices;

    public WorkOrderController(WorkOrderRepository repository, DeviceRepository devices) {
        this.repository = repository;
        this.devices = devices;
    }

    @GetMapping
    public List<WorkOrder> list() {
        return repository.findAllByOrderByUpdatedAtDesc();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkOrder create(@RequestBody CreateRequest request) {
        if (request.deviceCode() == null || request.deviceCode().isBlank()
                || request.title() == null || request.title().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编码和工单主题不能为空");
        }
        var device = devices.findById(request.deviceCode())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备不存在：" + request.deviceCode()));
        Instant now = Instant.now();
        String orderNo = "WO-DEMO-" + NUMBER_TIME.format(now) + "-" + String.format("%02d", SEQUENCE.getAndIncrement());
        WorkOrder workOrder = new WorkOrder(orderNo, device.code(), device.name(), request.title(),
                fallback(request.priority(), "P2 中"), "待审批", fallback(request.assignee(), device.owner()),
                fallback(request.source(), "人工创建"), fallback(request.description(), ""),
                fallback(request.plannedWindow(), "待确认"), now, now);
        return repository.save(workOrder);
    }

    @PatchMapping("/{orderNo}/status")
    public WorkOrder changeStatus(@PathVariable String orderNo, @RequestBody StatusRequest request) {
        if (request.status() == null || request.status().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "目标状态不能为空");
        }
        WorkOrder current = repository.findById(orderNo)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到工单：" + orderNo));
        return repository.save(current.changeStatus(request.status()));
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public record CreateRequest(String deviceCode, String title, String priority, String assignee,
                                String source, String description, String plannedWindow) {
    }

    public record StatusRequest(String status) {
    }
}
