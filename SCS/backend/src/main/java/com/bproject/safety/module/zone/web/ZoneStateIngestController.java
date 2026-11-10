package com.bproject.safety.module.zone.web;

import com.bproject.safety.module.zone.service.ZoneStateService;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 装卸区状态上报（网关经 X-API-Key 上报）。
 *
 * <p>与 {@code /api/v1/ingest/device-state} 同样放在 {@code /api/v1/ingest/} 前缀下：
 * {@code SecurityInterceptor} 只允许机器凭据访问该前缀，校验通过后直接放行。</p>
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "装卸区状态接入", description = "限位开关 / 位置传感器经网关上报区域状态（机器凭据）")
public class ZoneStateIngestController {

    private final ZoneStateService service;

    public ZoneStateIngestController(ZoneStateService service) {
        this.service = service;
    }

    /** 单个区域上报体；信号字段缺省保留，显式 null 清空。signal 仍沿用原有合并规则。 */
    public static class ZoneStateRequest {
        @JsonProperty
        private String code;
        private Boolean personnelWorking;
        private Boolean vehiclePresent;
        private Integer distanceMm;
        @JsonProperty
        private String signal;
        @JsonIgnore
        private boolean personnelWorkingProvided;
        @JsonIgnore
        private boolean vehiclePresentProvided;
        @JsonIgnore
        private boolean distanceMmProvided;

        @JsonSetter(value = "personnelWorking", nulls = Nulls.SET)
        public void setPersonnelWorking(Boolean value) {
            personnelWorking = value;
            personnelWorkingProvided = true;
        }

        @JsonSetter(value = "vehiclePresent", nulls = Nulls.SET)
        public void setVehiclePresent(Boolean value) {
            vehiclePresent = value;
            vehiclePresentProvided = true;
        }

        @JsonSetter(value = "distanceMm", nulls = Nulls.SET)
        public void setDistanceMm(Integer value) {
            distanceMm = value;
            distanceMmProvided = true;
        }
    }

    public record IngestRequest(String source, List<ZoneStateRequest> zones) {}

    @PostMapping("/zone-state")
    @Operation(summary = "上报装卸区状态，未知区域编码忽略")
    public Map<String, Object> zoneState(@RequestBody(required = false) IngestRequest body) {
        List<ZoneStateRequest> zones = body == null || body.zones() == null ? List.of() : body.zones();
        int applied = 0;
        List<String> ignored = new ArrayList<>();
        for (ZoneStateRequest z : zones) {
            if (z == null || z.code == null || z.code.isBlank()) {
                ignored.add("<empty>");
                continue;
            }
            boolean ok = service.apply(z.code, z.personnelWorking, z.vehiclePresent,
                    z.distanceMm, z.signal, z.personnelWorkingProvided,
                    z.vehiclePresentProvided, z.distanceMmProvided);
            if (ok) {
                applied++;
            } else {
                ignored.add(z.code);
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("applied", applied);
        result.put("ignored", ignored);
        result.put("source", body == null || body.source() == null ? "unknown" : body.source());
        return result;
    }
}
