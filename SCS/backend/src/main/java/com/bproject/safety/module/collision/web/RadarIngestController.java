package com.bproject.safety.module.collision.web;

import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.collision.service.CollisionService.ExternalRadarReading;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 雷达测距上报入口（IF-IN-RADAR，毫米波测距/激光雷达经只读网关上报）。
 *
 * <p>与 {@code /api/v1/ingest/device-state}、{@code zone-state}、{@code person-position}
 * 同一前缀：{@code SecurityInterceptor} 只允许机器凭据（X-API-Key）访问该前缀，
 * 校验通过后直接放行，不进浏览器会话的权限分支。</p>
 *
 * <p>与设备状态上报的区别：设备状态只反映设备自身运行态，<b>不推进碰撞距离</b>；
 * 本接口上报的就是相对距离本身，因此会驱动配对距离、碰撞风险等级与对应的告警升级。</p>
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "雷达测距接入", description = "毫米波雷达按配对设备上报相对距离与相对速度（机器凭据）")
public class RadarIngestController {

    private final CollisionService service;

    public RadarIngestController(CollisionService service) {
        this.service = service;
    }

    public record IngestRequest(String source, List<ExternalRadarReading> readings) {}

    @PostMapping("/radar")
    @Operation(summary = "上报雷达测距，未知配对忽略")
    public Map<String, Object> radar(@RequestBody(required = false) IngestRequest body) {
        List<ExternalRadarReading> readings = body == null || body.readings() == null
                ? List.of() : body.readings();
        CollisionService.IngestResult result = service.applyExternalRadar(readings);
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("applied", result.applied());
        resp.put("ignored", result.ignored());
        resp.put("source", body == null || body.source() == null ? "unknown" : body.source());
        return resp;
    }
}
