package com.bproject.safety.module.alert.web;

import com.bproject.safety.module.alert.dto.AlertRequests.AssignRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ConfirmRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.EscalateRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.LinkageRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ReviewRejectRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.ReviewRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.StartRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TakeoverRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TreatmentRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TransferRequest;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertQuery;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.alert.service.AlertMetrics;
import com.bproject.safety.module.alert.service.AlertService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 告警中心接口（Backend Demo）。成功响应直接返回业务对象/分页结构，不做统一包装。
 */
@RestController
@RequestMapping("/api/v1/alerts")
@Tag(name = "Alert", description = "告警中心与处置闭环（Demo 内存数据）")
public class AlertController {

    private final AlertService service;

    public AlertController(AlertService service) {
        this.service = service;
    }

    @Operation(summary = "顶部统计指标（按当前数据实时计算）")
    @GetMapping("/metrics")
    public AlertMetrics metrics() {
        return service.metrics();
    }

    @Operation(summary = "告警列表（多条件筛选 + 分页 + SLA 剩余秒数）")
    @GetMapping
    public AlertRepository.AlertPageResult list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String risk,
            @RequestParam(required = false) @Parameter(description = "风险等级别名，等同 risk") String level,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String area,
            @RequestParam(required = false) String eventType,
            @RequestParam(required = false) @Parameter(description = "事件类型别名，等同 eventType") String type,
            @RequestParam(required = false) String source,
            @RequestParam(required = false) String assignee,
            @RequestParam(required = false) @Parameter(description = "责任人别名，等同 assignee") String owner,
            @RequestParam(required = false) String timeRange,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer pageSize) {
        AlertQuery query = AlertQuery.of(keyword, risk, status, area, eventType, source, assignee,
                timeRange, from, to, level, type, owner, page, pageSize);
        return service.page(query);
    }

    @Operation(summary = "告警详情（证据 / 联动 / 时间线 / 处置记录）")
    @GetMapping("/{id}")
    public DemoAlert detail(@PathVariable String id) {
        return service.get(id);
    }

    @Operation(summary = "确认事件：待确认 → 待派单",
            parameters = @Parameter(name = "Idempotency-Key", description = "幂等键（前端自动携带）"))
    @PostMapping("/{id}/confirm")
    public DemoAlert confirm(@PathVariable String id,
                             @RequestBody(required = false) ConfirmRequest body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.confirm(id, body, idemKey);
    }

    @Operation(summary = "派单：待派单 → 待处理")
    @PostMapping("/{id}/assign")
    public DemoAlert assign(@PathVariable String id,
                            @RequestBody(required = false) AssignRequest body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.assign(id, body, idemKey);
    }

    @Operation(summary = "接单 / 开始处理：待处理 → 处理中")
    @PostMapping("/{id}/start")
    public DemoAlert start(@PathVariable String id,
                           @RequestBody(required = false) StartRequest body,
                           @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.start(id, body, idemKey);
    }

    @Operation(summary = "提交处置结果：处理中 → 待复核")
    @PostMapping("/{id}/treatment")
    public DemoAlert treatment(@PathVariable String id,
                               @RequestBody(required = false) TreatmentRequest body,
                               @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.treatment(id, body, idemKey);
    }

    @Operation(summary = "复核通过并关闭：待复核 → 已关闭")
    @PostMapping("/{id}/review")
    public DemoAlert review(@PathVariable String id,
                            @RequestBody(required = false) ReviewRequest body,
                            @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.review(id, body, idemKey);
    }

    @Operation(summary = "复核驳回：待复核 → 处理中")
    @PostMapping("/{id}/review-reject")
    public DemoAlert reviewReject(@PathVariable String id,
                                  @RequestBody(required = false) ReviewRejectRequest body,
                                  @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.reviewReject(id, body, idemKey);
    }

    @Operation(summary = "转派责任人（状态不变）")
    @PostMapping("/{id}/transfer")
    public DemoAlert transfer(@PathVariable String id,
                              @RequestBody(required = false) TransferRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.transfer(id, body, idemKey);
    }

    @Operation(summary = "事件升级")
    @PostMapping("/{id}/escalate")
    public DemoAlert escalate(@PathVariable String id,
                              @RequestBody(required = false) EscalateRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.escalate(id, body, idemKey);
    }

    @Operation(summary = "人工接管（PLC 联动失败兜底，仅记录业务）")
    @PostMapping("/{id}/takeover")
    public DemoAlert takeover(@PathVariable String id,
                              @RequestBody(required = false) TakeoverRequest body,
                              @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.takeover(id, body, idemKey);
    }

    @Operation(summary = "发起联动演示（mode=success 成功链路；mode=fail PLC 回执失败）")
    @PostMapping("/{id}/linkage")
    public DemoAlert linkage(@PathVariable String id,
                             @RequestBody(required = false) LinkageRequest body,
                             @RequestHeader(value = "Idempotency-Key", required = false) String idemKey) {
        return service.linkage(id, body, idemKey);
    }
}
