package com.bproject.safety.module.ops.web;

import com.bproject.safety.module.ops.model.DemoEdgeNode;
import com.bproject.safety.module.ops.model.EdgePendingEvent;
import com.bproject.safety.module.ops.service.EdgeOpsService;
import com.bproject.safety.module.ops.web.OpsRequests.RecoverRequest;
import com.bproject.safety.module.ops.web.OpsRequests.ReconcileRulesRequest;
import com.bproject.safety.module.ops.web.OpsRequests.ReconcileTimeRequest;
import com.bproject.safety.module.ops.web.OpsRequests.SimulateLinkRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 云边断网自治接口（路径严格对齐 inventory 的 /edge 段）。
 *
 * <p>SIMULATED EDGE AUTONOMY：断网 / 恢复 / 本地判定 / 补传均为同一 Spring Boot 进程内的
 * 架构行为模拟，不是真实部署了边缘 Agent；断网期间事件只入离线队列，不进云端 Alert。
 * 写操作重复安全由 Service 状态机与队列幂等层保证。</p>
 */
@RestController
@RequestMapping("/api/v1/edge")
@Tag(name = "云边断网自治", description = "断网模拟 / 离线本地事件 / 恢复对账 / 幂等补传（Demo）")
public class EdgeAutonomyController {

    private static final String DEFAULT_NODE = "EDGE-03";

    private final EdgeOpsService service;

    public EdgeAutonomyController(EdgeOpsService service) {
        this.service = service;
    }

    @GetMapping("/link")
    @Operation(summary = "云边链路聚合状态（online / recovering / link-error / disconnected）")
    public Map<String, Object> link() {
        return service.link();
    }

    @PostMapping("/simulate-link")
    @Operation(summary = "模拟断网 / 恢复连接（state=disconnect|recover，默认 EDGE-03）")
    public DemoEdgeNode simulateLink(@RequestBody(required = false) SimulateLinkRequest body) {
        String nodeId = body != null && body.nodeId() != null ? body.nodeId() : DEFAULT_NODE;
        String state = body == null || body.state() == null ? "disconnect" : body.state();
        return switch (state) {
            case "disconnect" -> service.simulateDisconnect(nodeId);
            case "recover" -> service.startRecovery(nodeId);
            default -> throw new IllegalArgumentException("非法链路状态: " + state);
        };
    }

    @GetMapping("/local-events")
    @Operation(summary = "边缘离线事件队列（可按节点 / 状态过滤）")
    public Map<String, List<EdgePendingEvent>> localEvents(@RequestParam(required = false) String node,
                                                           @RequestParam(required = false) String status) {
        return Map.of("list", service.localEvents(node, status));
    }

    @PostMapping("/local-events")
    @Operation(summary = "断网期间产生边缘本地风险事件（本地判定 + 本地联动，入离线队列，不进云端 Alert）")
    public EdgePendingEvent createLocalEvent(@RequestBody(required = false) EdgeOpsService.LocalEventRequest body) {
        EdgeOpsService.LocalEventRequest req = body == null
                ? new EdgeOpsService.LocalEventRequest(DEFAULT_NODE, null, null, null, null, null) : body;
        return service.createLocalEvent(req);
    }

    @PostMapping("/recover")
    @Operation(summary = "启动 / 推进恢复流程（CONNECTIVITY→CLOCK→RULE→EVENT_REPLAY→FINAL_CHECK→ONLINE）；重复调用兼作失败重试")
    public DemoEdgeNode recover(@RequestBody(required = false) RecoverRequest body) {
        String nodeId = body != null && body.nodeId() != null ? body.nodeId() : DEFAULT_NODE;
        // 恢复推进是重复安全的：同一阶段重复调用不会重复补传（队列层幂等兜底），因此每次都允许执行
        return service.startRecovery(nodeId);
    }

    @GetMapping("/reconcile/rules")
    @Operation(summary = "规则 / 围栏版本对账视图（边缘版本 vs 平台期望版本）")
    public Map<String, Object> ruleReconcile() {
        return service.ruleReconcileView();
    }

    @PostMapping("/reconcile/rules")
    @Operation(summary = "规则版本对账（action=redeliver 重新下发 / keep 本次放行），并继续推进恢复")
    public DemoEdgeNode reconcileRules(@RequestBody(required = false) ReconcileRulesRequest body,
                                       @RequestParam(required = false) String nodeId) {
        String node = nodeId != null ? nodeId : DEFAULT_NODE;
        String action = body == null ? "redeliver" : body.action();
        return service.reconcileRules(node, action);
    }

    @PostMapping("/reconcile/time")
    @Operation(summary = "时间对账（Demo 校时，非真实 NTP），并继续推进恢复")
    public DemoEdgeNode reconcileTime(@RequestBody(required = false) ReconcileTimeRequest body,
                                      @RequestParam(required = false) String nodeId) {
        String node = nodeId != null ? nodeId
                : (body != null && body.nodeId() != null ? body.nodeId() : DEFAULT_NODE);
        return service.reconcileTime(node);
    }
}
