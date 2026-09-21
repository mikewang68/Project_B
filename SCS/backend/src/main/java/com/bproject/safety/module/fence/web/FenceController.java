package com.bproject.safety.module.fence.web;

import com.bproject.safety.module.fence.dto.FenceRequests.CreateFenceRequest;
import com.bproject.safety.module.fence.dto.FenceRequests.PublishRequest;
import com.bproject.safety.module.fence.dto.FenceRequests.RedeliverRequest;
import com.bproject.safety.module.fence.dto.FenceRequests.UpdateFenceRequest;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.service.FenceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 电子围栏接口：列表 / 详情 / 新建 / 编辑 / 评审 / 发布 / 重下发 / 停用 / 版本异常模拟。 */
@RestController
@RequestMapping("/api/v1/fences")
@Tag(name = "电子围栏", description = "围栏配置、评审发布与边缘节点同步（Demo）")
public class FenceController {

    private final FenceService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public FenceController(FenceService service,
                           com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping
    @Operation(summary = "围栏列表")
    public Map<String, List<DemoFence>> list(@RequestParam(required = false) String keyword,
                                             @RequestParam(required = false) String status,
                                             @RequestParam(required = false) String kind) {
        return Map.of("list", service.list(keyword, status, kind));
    }

    @GetMapping("/{id}")
    @Operation(summary = "围栏详情（含边缘节点同步状态）")
    public DemoFence detail(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @Operation(summary = "新建围栏（草稿或直接提交评审）")
    public DemoFence create(@RequestBody CreateFenceRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑围栏")
    public DemoFence update(@PathVariable String id, @RequestBody UpdateFenceRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/submit-review")
    @Operation(summary = "提交评审")
    public DemoFence submitReview(@PathVariable String id) {
        return service.submitReview(id);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "发布并模拟边缘节点下发")
    public DemoFence publish(@PathVariable String id, @RequestBody(required = false) PublishRequest request) {
        return service.publish(id);
    }

    @PostMapping("/{id}/redeliver")
    @Operation(summary = "指定边缘节点重新下发")
    public DemoFence redeliver(@PathVariable String id, @RequestBody(required = false) RedeliverRequest request) {
        return service.redeliver(id, request == null ? null : request.nodeId());
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用围栏")
    public DemoFence disable(@PathVariable String id) {
        return service.disable(id);
    }

    @PostMapping("/{id}/simulate-mismatch")
    @Operation(summary = "模拟边缘版本不一致（SIMULATED）")
    public DemoFence simulateMismatch(@PathVariable String id) {
        demoGuard.requireSimulator();
        return service.simulateMismatch(id);
    }
}
