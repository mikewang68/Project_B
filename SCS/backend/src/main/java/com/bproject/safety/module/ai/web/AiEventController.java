package com.bproject.safety.module.ai.web;

import com.bproject.safety.module.ai.dto.AiDtos.AiEventPage;
import com.bproject.safety.module.ai.dto.AiRequests.AiAssignRequest;
import com.bproject.safety.module.ai.dto.AiRequests.FalsePositiveRequest;
import com.bproject.safety.module.ai.dto.AiRequests.ProcessRequest;
import com.bproject.safety.module.ai.dto.AiRequests.ReviewRequest;
import com.bproject.safety.module.ai.dto.AiRequests.SimulateRequest;
import com.bproject.safety.module.ai.dto.AiRequests.UncertainRequest;
import com.bproject.safety.module.ai.model.CameraInfo;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventQuery;
import com.bproject.safety.module.ai.service.AiEventService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 违规识别接口：列表 / 详情 / 人工复核 / 派单 / 模拟事件 / 摄像头健康。
 * 成功响应直接返回业务体（不包装 code/data），错误走全局统一错误结构。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "AI违规识别", description = "AI 事件查询、人工复核与派单（确认违规后进入 Alert 主链）")
public class AiEventController {

    private final AiEventService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public AiEventController(AiEventService service,
                             com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping("/ai-events")
    @Operation(summary = "AI 事件列表（后端筛选+分页，响应同时带指标与筛选项）")
    public AiEventPage list(@RequestParam(required = false) String keyword,
                            @RequestParam(required = false) String type,
                            @RequestParam(required = false) String area,
                            @RequestParam(required = false) String camera,
                            @RequestParam(required = false) String status,
                            @RequestParam(required = false) String risk,
                            @RequestParam(required = false) String confidence,
                            @RequestParam(required = false, name = "timeBucket") String timeBucket,
                            @RequestParam(required = false) Integer page,
                            @RequestParam(required = false) Integer pageSize) {
        AiEventQuery query = AiEventQuery.of(keyword, type, area, camera, status, risk,
                confidence, timeBucket, page, pageSize);
        return service.page(query);
    }

    @GetMapping("/ai-events/{id}")
    @Operation(summary = "AI 事件详情（检测框 / 时间线 / 关联告警）")
    public DemoAiEvent detail(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping("/ai-events/{id}/confirm")
    @Operation(summary = "确认违规：创建关联 Alert 并广播 alert.new（重复确认不重复建）")
    public DemoAiEvent confirm(@PathVariable String id,
                               @RequestBody(required = false) ReviewRequest body,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.confirm(id, body, idemKey);
    }

    @PostMapping("/ai-events/{id}/false-positive")
    @Operation(summary = "标记误报：记录原因，不创建 Alert")
    public DemoAiEvent falsePositive(@PathVariable String id,
                                     @RequestBody(required = false) FalsePositiveRequest body,
                                     @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.falsePositive(id, body, idemKey);
    }

    @PostMapping("/ai-events/{id}/uncertain")
    @Operation(summary = "暂不确定：转入人工复核队列")
    public DemoAiEvent uncertain(@PathVariable String id,
                                 @RequestBody(required = false) UncertainRequest body,
                                 @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.uncertain(id, body, idemKey);
    }

    @PostMapping("/ai-events/{id}/assign")
    @Operation(summary = "AI 派单：必要时先创建 Alert，再复用 Alert 派单主链")
    public DemoAiEvent assign(@PathVariable String id,
                              @RequestBody(required = false) AiAssignRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.assign(id, body, idemKey);
    }

    @PostMapping("/ai-events/{id}/process")
    @Operation(summary = "标记处理中")
    public DemoAiEvent process(@PathVariable String id,
                               @RequestBody(required = false) ProcessRequest body,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.process(id, body, idemKey);
    }

    @PostMapping("/ai-events/{id}/close")
    @Operation(summary = "关闭 AI 事件（不联动关闭 Alert）")
    public DemoAiEvent close(@PathVariable String id,
                             @RequestBody(required = false) ProcessRequest body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.close(id, body, idemKey);
    }

    @PostMapping("/ai-events/simulate")
    @Operation(summary = "模拟 AI 事件（SIMULATED）：kind=new/low-confidence/camera-fault")
    public DemoAiEvent simulate(@RequestBody(required = false) SimulateRequest body,
                                @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        demoGuard.requireSimulator();
        return service.simulate(body, idemKey);
    }

    @GetMapping("/cameras")
    @Operation(summary = "摄像头健康台账（Demo）")
    public List<CameraInfo> cameras() {
        return service.cameras();
    }
}
