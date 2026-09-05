package com.bproject.ehm.web;

import com.bproject.ehm.domain.Alarm;
import com.bproject.ehm.domain.Device;
import com.bproject.ehm.domain.WorkOrder;
import com.bproject.ehm.repository.AlarmRepository;
import com.bproject.ehm.repository.DeviceRepository;
import com.bproject.ehm.repository.WorkOrderRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/ehm/v1/dashboard")
public class DashboardController {
    private final DeviceRepository devices;
    private final AlarmRepository alarms;
    private final WorkOrderRepository workOrders;

    public DashboardController(DeviceRepository devices, AlarmRepository alarms, WorkOrderRepository workOrders) {
        this.devices = devices;
        this.alarms = alarms;
        this.workOrders = workOrders;
    }

    @GetMapping("/summary")
    public Map<String, Object> summary() {
        List<Device> deviceList = devices.findAll();
        List<Alarm> alarmList = alarms.findAll();
        List<WorkOrder> orderList = workOrders.findAll();

        long online = deviceList.stream().filter(this::isOnline).count();
        long assessable = deviceList.stream().filter(device -> device.health() != null).count();
        long healthy = deviceList.stream().filter(device -> device.health() != null && device.health() >= 80).count();
        long highRisk = deviceList.stream().filter(device -> "severe".equals(device.riskClass()) || "critical".equals(device.riskClass())).count();
        long openAlarms = alarmList.stream().filter(alarm -> !"已关闭".equals(alarm.status())).count();
        long criticalOpen = alarmList.stream().filter(alarm -> !"已关闭".equals(alarm.status()) && "severe".equals(alarm.levelClass())).count();
        long activeOrders = orderList.stream().filter(order -> !List.of("已关闭", "已取消").contains(order.status())).count();
        long pendingOrders = orderList.stream().filter(order -> List.of("待审批", "待执行").contains(order.status())).count();
        double averageHealth = deviceList.stream().filter(device -> device.health() != null).mapToInt(Device::health).average().orElse(0);
        double averageQuality = deviceList.stream().filter(device -> device.quality() != null).mapToDouble(Device::quality).average().orElse(0);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("totalDevices", deviceList.size());
        result.put("onlineDevices", online);
        result.put("onlineRate", percent(online, deviceList.size()));
        result.put("assessableDevices", assessable);
        result.put("healthyDevices", healthy);
        result.put("highRiskDevices", highRisk);
        result.put("openAlarms", openAlarms);
        result.put("criticalOpenAlarms", criticalOpen);
        result.put("activeWorkOrders", activeOrders);
        result.put("pendingWorkOrders", pendingOrders);
        result.put("averageHealth", Math.round(averageHealth * 10.0) / 10.0);
        result.put("dataQuality", Math.round(averageQuality * 10.0) / 10.0);
        result.put("updatedAt", Instant.now());
        return result;
    }

    private boolean isOnline(Device device) {
        return !List.of("离线", "停机", "已归档").contains(device.condition());
    }

    private double percent(long value, long total) {
        return total == 0 ? 0 : Math.round(value * 1000.0 / total) / 10.0;
    }
}
