package com.bproject.ehm.asset.adapter.in.web;

import com.bproject.ehm.asset.application.DeviceApplicationService;
import com.bproject.ehm.asset.application.DeviceCommand;
import com.bproject.ehm.asset.application.DeviceView;
import com.bproject.ehm.shared.page.PageQuery;
import com.bproject.ehm.shared.page.PageResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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

@RestController
@RequestMapping("/api/ehm/v1/devices")
public class DeviceController {
    private final DeviceApplicationService devices;

    public DeviceController(DeviceApplicationService devices) {
        this.devices = devices;
    }

    @GetMapping
    public PageResult<DeviceView> list(@RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "50") int size,
                                       @RequestParam(required = false) String keyword,
                                       @RequestParam(required = false) String area,
                                       @RequestParam(required = false) String type) {
        return devices.list(new PageQuery(page, size), keyword, area, type);
    }

    @GetMapping("/{code}")
    public DeviceView get(@PathVariable String code) {
        return devices.get(code);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public DeviceView create(@Valid @RequestBody DeviceRequest request) {
        return devices.create(request.toCommand());
    }

    @PutMapping("/{code}")
    public DeviceView update(@PathVariable String code, @Valid @RequestBody DeviceRequest request) {
        return devices.update(code, request.toCommand());
    }

    @DeleteMapping("/{code}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void archive(@PathVariable String code) {
        devices.archive(code);
    }

    public record DeviceRequest(
            String code,
            @NotBlank(message = "设备名称不能为空") String name,
            String type,
            String area,
            String condition,
            Integer health,
            String risk,
            String riskClass,
            Double quality,
            String ready,
            String alarm,
            String maintenanceDate,
            String owner,
            Double temperature,
            Double vibration,
            Double current,
            Integer rulDays
    ) {
        DeviceCommand toCommand() {
            return new DeviceCommand(code, name, type, area, condition, health, risk, riskClass, quality,
                    ready, alarm, maintenanceDate, owner, temperature, vibration, current, rulDays);
        }
    }
}
