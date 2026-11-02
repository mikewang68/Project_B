package com.bproject.safety.module.collision.web;

import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.collision.service.CollisionService.ExternalDeviceState;
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
 * 设备运行状态上报入口（网关 / 边缘节点）。
 *
 * <p>放在 {@code /api/v1/ingest/} 前缀下是刻意为之：{@code SecurityInterceptor} 只允许
 * 机器凭据（X-API-Key）访问该前缀，且在校验通过后直接放行、不再经过浏览器的
 * {@code ScsLocalPolicy.permits}。这样既不新增一条鉴权分支，也不会把设备上报暴露给浏览器会话。</p>
 *
 * <p>本接口只更新已登记设备的展示状态，不创建设备、不推进碰撞距离、不产生告警。</p>
 */
@RestController
@RequestMapping("/api/v1/ingest")
@Tag(name = "设备状态接入", description = "网关按设备协议采集后上报运行状态（机器凭据）")
public class DeviceStateIngestController {

    private final CollisionService service;

    public DeviceStateIngestController(CollisionService service) {
        this.service = service;
    }

    public record IngestRequest(String source, List<ExternalDeviceState> devices) {}

    @PostMapping("/device-state")
    @Operation(summary = "上报设备运行状态（龙门吊 / 翻箱机等），未知设备编码忽略")
    public Map<String, Object> deviceState(@RequestBody(required = false) IngestRequest body) {
        List<ExternalDeviceState> devices = body == null || body.devices() == null
                ? List.of() : body.devices();
        CollisionService.IngestResult result = service.applyExternalState(devices);
        Map<String, Object> body2 = new LinkedHashMap<>();
        body2.put("applied", result.applied());
        body2.put("ignored", result.ignored());
        body2.put("source", body == null || body.source() == null ? "unknown" : body.source());
        return body2;
    }
}
