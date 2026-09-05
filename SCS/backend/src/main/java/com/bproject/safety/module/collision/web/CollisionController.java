package com.bproject.safety.module.collision.web;

import com.bproject.safety.module.collision.model.CollisionStep;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.model.DistancePoint;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.collision.service.CollisionService.PairView;
import com.bproject.safety.module.collision.service.CollisionService.ReleaseResult;
import com.bproject.safety.module.collision.service.CollisionService.SimulateResult;
import com.bproject.safety.module.collision.service.CollisionService.TakeoverResult;
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

/** 设备防碰撞接口：设备 / 配对距离 / 趋势 / 模拟 / 联动 / 人工接管 / 解除申请。 */
@RestController
@RequestMapping("/api/v1/collision")
@Tag(name = "设备防碰撞", description = "设备距离风险计算与联动（严重/紧急风险进入 Alert 主链）")
public class CollisionController {

    private final CollisionService service;

    public CollisionController(CollisionService service) {
        this.service = service;
    }

    @GetMapping("/devices")
    @Operation(summary = "防碰撞设备列表（风险由配对实时状态装饰）")
    public Map<String, List<DemoCollisionDevice>> devices(@RequestParam(required = false) String type) {
        return Map.of("list", service.devices(type));
    }

    @GetMapping("/devices/{id}")
    @Operation(summary = "设备详情")
    public DemoCollisionDevice detail(@PathVariable String id) {
        return service.get(id);
    }

    @GetMapping("/pair")
    @Operation(summary = "设备对实时距离 / 相对速度 / 风险")
    public PairView pair(@RequestParam(required = false) String currentId,
                         @RequestParam(required = false) String relatedId) {
        return service.pair(currentId, relatedId);
    }

    @GetMapping("/devices/{id}/distance-trend")
    @Operation(summary = "距离趋势序列")
    public Map<String, List<DistancePoint>> trend(@PathVariable String id) {
        return Map.of("points", service.trend(id));
    }

    @PostMapping("/simulate")
    @Operation(summary = "模拟（SIMULATED：approach 逐档接近 / radarDown / linkageFail / reset）")
    public SimulateResult simulate(@RequestBody Map<String, String> body) {
        String deviceId = body == null ? "VEH-07" : body.getOrDefault("deviceId", "VEH-07");
        String scenario = body == null ? "approach" : body.getOrDefault("scenario", "approach");
        return service.simulate(deviceId, scenario);
    }

    @PostMapping("/linkage")
    @Operation(summary = "发起联动（success/fail 演示，步骤由后端保存）")
    public Map<String, List<CollisionStep>> linkage(@RequestBody Map<String, String> body) {
        String deviceId = body == null ? "VEH-07" : body.getOrDefault("deviceId", "VEH-07");
        String mode = body == null ? "success" : body.getOrDefault("mode", "success");
        return Map.of("steps", service.linkage(deviceId, mode));
    }

    @PostMapping("/takeover")
    @Operation(summary = "人工接管（复用 Alert 接管能力，写入同一 Timeline）")
    public TakeoverResult takeover(@RequestBody Map<String, String> body) {
        String deviceId = body == null ? "VEH-07" : body.getOrDefault("deviceId", "VEH-07");
        String operator = body == null ? null : body.get("operator");
        String note = body == null ? null : body.get("note");
        return service.takeover(deviceId, operator, note);
    }

    @PostMapping("/release-request")
    @Operation(summary = "申请解除运行限制（Demo 仅记录，不建审批流）")
    public ReleaseResult release(@RequestBody Map<String, Object> body) {
        String deviceId = body == null ? "VEH-07" : String.valueOf(body.getOrDefault("deviceId", "VEH-07"));
        String operator = body == null ? null : (String) body.get("operator");
        @SuppressWarnings("unchecked")
        List<String> checks = body == null ? List.of() : (List<String>) body.getOrDefault("checks", List.of());
        return service.releaseRequest(deviceId, checks, operator);
    }
}
