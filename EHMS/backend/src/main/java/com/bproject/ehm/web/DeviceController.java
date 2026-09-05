package com.bproject.ehm.web;

import com.bproject.ehm.domain.Device;
import com.bproject.ehm.repository.DeviceRepository;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

@RestController
@RequestMapping("/api/ehm/v1/devices")
public class DeviceController {
    private final DeviceRepository repository;

    public DeviceController(DeviceRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    public List<Device> list(@RequestParam(required = false) String keyword,
                             @RequestParam(required = false) String area,
                             @RequestParam(required = false) String type) {
        String normalized = keyword == null ? "" : keyword.trim().toLowerCase(Locale.ROOT);
        return repository.findAll().stream()
                .filter(device -> !"已归档".equals(device.condition()))
                .filter(device -> normalized.isEmpty()
                        || contains(device.code(), normalized)
                        || contains(device.name(), normalized))
                .filter(device -> area == null || area.isBlank() || area.equals(device.area()))
                .filter(device -> type == null || type.isBlank() || type.equals(device.type()))
                .sorted(Comparator.comparing(Device::code))
                .toList();
    }

    @GetMapping("/{code}")
    public Device get(@PathVariable String code) {
        return repository.findById(code).orElseThrow(() -> notFound(code));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Device create(@RequestBody Device request) {
        if (request.code() == null || request.code().isBlank() || request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "设备编码和名称不能为空");
        }
        String code = request.code().trim().toUpperCase(Locale.ROOT);
        if (repository.existsById(code)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "设备编码已存在：" + code);
        }
        return repository.save(withCodeAndTime(request, code));
    }

    @PutMapping("/{code}")
    public Device update(@PathVariable String code, @RequestBody Device request) {
        if (!repository.existsById(code)) throw notFound(code);
        return repository.save(withCodeAndTime(request, code));
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String code) {
        Device current = repository.findById(code).orElseThrow(() -> notFound(code));
        Device archived = new Device(current.code(), current.name(), current.type(), current.area(), "已归档",
                current.health(), "已停用", "offline", current.quality(), "停用", current.alarm(),
                current.maintenanceDate(), current.owner(), current.temperature(), current.vibration(),
                current.current(), current.rulDays(), Instant.now());
        repository.save(archived);
    }

    private Device withCodeAndTime(Device request, String code) {
        return new Device(code, request.name(), fallback(request.type(), "未分类"), fallback(request.area(), "未分区"),
                fallback(request.condition(), "待接入"), request.health(), fallback(request.risk(), "待评估"),
                fallback(request.riskClass(), "limited"), request.quality(), fallback(request.ready(), "待接入"),
                fallback(request.alarm(), "无活动告警"), request.maintenanceDate(), fallback(request.owner(), "待分配"),
                request.temperature(), request.vibration(), request.current(), request.rulDays(), Instant.now());
    }

    private String fallback(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean contains(String value, String keyword) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(keyword);
    }

    private ResponseStatusException notFound(String code) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "未找到设备：" + code);
    }
}
