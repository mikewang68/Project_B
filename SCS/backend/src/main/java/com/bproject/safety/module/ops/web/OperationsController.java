package com.bproject.safety.module.ops.web;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.OpsEventLog;
import com.bproject.safety.module.ops.service.EdgeOpsService;
import com.bproject.safety.module.ops.service.OpsInventory;
import com.bproject.safety.module.ops.web.OpsRequests.MaintainRequest;
import com.bproject.safety.module.ops.web.OpsRequests.SimulateRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 运维监控接口（路径严格对齐 docs/backend-demo-api-inventory.json 的 /ops 段）。
 * SIMULATED EDGE AUTONOMY：边缘节点为同进程行为模拟。
 *
 * <p>写操作的重复安全由 Service 状态机与离线队列幂等层兜底（断网重复调用抛 409、
 * 补传按 eventId / Idempotency-Key 去重、恢复重复调用不重复补传），前端对非 GET
 * 请求自动携带 Idempotency-Key。</p>
 */
@RestController
@RequestMapping("/api/v1/ops")
@Tag(name = "运维监控", description = "运维总览 / 边缘节点 / 设备接口健康 / 云边断网自治（Demo）")
public class OperationsController {

    private final EdgeOpsService service;

    public OperationsController(EdgeOpsService service) {
        this.service = service;
    }

    @GetMapping("/overview")
    @Operation(summary = "运维总览聚合（节点 / 队列 / 同步 / 接口健康，实时计算）")
    public Map<String, Object> overview() {
        return service.overview();
    }

    @GetMapping("/topology")
    @Operation(summary = "云边拓扑（Cloud → Edge → Device，链路状态来自 Backend）")
    public Map<String, Object> topology() {
        return service.topology();
    }

    @GetMapping("/edge-nodes")
    @Operation(summary = "边缘节点列表")
    public Map<String, List<DemoEdgeNode>> edgeNodes() {
        return Map.of("list", service.listNodes());
    }

    @GetMapping("/edge-nodes/{id}")
    @Operation(summary = "边缘节点详情（含趋势样本与本地缓存事件）")
    public Map<String, Object> edgeNode(@PathVariable String id) {
        return service.nodeDetail(id);
    }

    @PostMapping("/edge-nodes/{id}/maintain")
    @Operation(summary = "节点维护：reconnect 发起恢复 / resyncTime 校时 / redeliverRule 重下发规则")
    public DemoEdgeNode maintain(@PathVariable String id, @RequestBody(required = false) MaintainRequest body) {
        String action = body == null ? "reconnect" : body.action();
        return service.maintain(id, action);
    }

    @GetMapping("/devices")
    @Operation(summary = "现场设备台账与健康状态")
    public Map<String, List<OpsInventory.OpsDevice>> devices() {
        return Map.of("list", service.devices());
    }

    @PostMapping("/devices/{id}/reconnect")
    @Operation(summary = "设备重新连接（故障恢复演示）")
    public OpsInventory.OpsDevice reconnectDevice(@PathVariable String id) {
        return service.reconnectDevice(id);
    }

    @GetMapping("/interfaces")
    @Operation(summary = "接口链路健康状态")
    public Map<String, List<OpsInventory.OpsInterface>> interfaces() {
        return Map.of("list", service.interfaces());
    }

    @GetMapping("/events")
    @Operation(summary = "运维事件日志（nodeId / level / type / from / to 过滤，最近记录）")
    public Map<String, List<OpsEventLog>> events(@RequestParam(required = false) Integer limit,
                                                 @RequestParam(required = false) String nodeId,
                                                 @RequestParam(required = false) String level,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String from,
                                                 @RequestParam(required = false) String to) {
        return Map.of("list", service.events(limit, nodeId, level, type, from, to));
    }

    @PostMapping("/simulate")
    @Operation(summary = "运维模拟场景：deviceFault 设备故障 / cacheAlert 缓存告警 / timeDrift 时钟漂移")
    public Map<String, Object> simulate(@RequestBody SimulateRequest body) {
        return service.simulate(body.scenario(), body.target());
    }
}
