package com.bproject.safety.module.rule.web;

import com.bproject.safety.module.rule.dto.RuleRequests.ConflictCheckRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.CreateRuleRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.OperatorRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.PublishRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.RedeliverRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.RollbackRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.SimulateRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.UpdateRuleRequest;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.service.RuleService;
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

/** 规则配置接口：KPI / 列表 / 详情 / 生命周期 / 发布同步 / 回滚 / 仿真 / 冲突检查（Backend Demo）。 */
@RestController
@RequestMapping("/api/v1/rules")
@Tag(name = "规则配置", description = "安全判定、告警升级与设备联动策略的统一管理（Demo）")
public class RuleController {

    private final RuleService service;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public RuleController(RuleService service,
                          com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.service = service;
        this.demoGuard = demoGuard;
    }

    @GetMapping("/metrics")
    @Operation(summary = "规则 KPI")
    public Map<String, Object> metrics() {
        return service.metrics();
    }

    @GetMapping
    @Operation(summary = "规则列表（分类 / 关键字 / 状态 / 风险筛选）")
    public Map<String, List<DemoRule>> list(@RequestParam(required = false) String keyword,
                                            @RequestParam(required = false) String category,
                                            @RequestParam(required = false) String status,
                                            @RequestParam(required = false) String risk) {
        return Map.of("list", service.list(keyword, category, status, risk));
    }

    @GetMapping("/{id}")
    @Operation(summary = "规则详情（参数 / 动作 / 版本 / 边缘同步）")
    public DemoRule detail(@PathVariable String id) {
        return service.get(id);
    }

    @PostMapping
    @Operation(summary = "新建规则（草稿或直接提交评审）")
    public DemoRule create(@RequestBody CreateRuleRequest request) {
        return service.create(request);
    }

    @PutMapping("/{id}")
    @Operation(summary = "编辑规则（草稿直接改；已生效规则以新建版本方式修改）")
    public DemoRule update(@PathVariable String id, @RequestBody UpdateRuleRequest request) {
        return service.update(id, request);
    }

    @PostMapping("/{id}/submit")
    @Operation(summary = "提交评审：草稿 → 待评审")
    public DemoRule submit(@PathVariable String id, @RequestBody(required = false) OperatorRequest request) {
        return service.submit(id, request);
    }

    @PostMapping("/{id}/approve")
    @Operation(summary = "批准：待评审 → 已批准（高危需 confirmHighRisk）")
    public DemoRule approve(@PathVariable String id, @RequestBody(required = false) OperatorRequest request) {
        return service.approve(id, request);
    }

    @PostMapping("/{id}/reject")
    @Operation(summary = "驳回：待评审 → 草稿")
    public DemoRule reject(@PathVariable String id, @RequestBody(required = false) OperatorRequest request) {
        return service.reject(id, request);
    }

    @PostMapping("/{id}/publish")
    @Operation(summary = "发布：已批准 → 已生效，模拟 4 个边缘节点同步成功")
    public DemoRule publish(@PathVariable String id, @RequestBody(required = false) PublishRequest request) {
        return service.publish(id, request);
    }

    @PostMapping("/{id}/redeliver")
    @Operation(summary = "边缘节点重新下发，全部同步后由版本异常恢复已生效")
    public DemoRule redeliver(@PathVariable String id, @RequestBody(required = false) RedeliverRequest request) {
        return service.redeliver(id, request);
    }

    @PostMapping("/{id}/rollback")
    @Operation(summary = "回滚：不覆盖当前版本，基于历史版本生成新版本并重新进入评审")
    public Map<String, Object> rollback(@PathVariable String id, @RequestBody RollbackRequest request) {
        return service.rollback(id, request);
    }

    @PostMapping("/{id}/disable")
    @Operation(summary = "停用规则")
    public DemoRule disable(@PathVariable String id, @RequestBody(required = false) OperatorRequest request) {
        return service.disable(id, request);
    }

    @GetMapping("/{id}/versions")
    @Operation(summary = "规则版本历史")
    public Map<String, List<Version>> versions(@PathVariable String id) {
        return Map.of("versions", service.versions(id));
    }

    @PostMapping("/{id}/simulate-mismatch")
    @Operation(summary = "模拟边缘版本不一致（SIMULATED）")
    public DemoRule simulateMismatch(@PathVariable String id,
                                     @RequestBody(required = false) OperatorRequest request) {
        demoGuard.requireSimulator();
        return service.simulateMismatch(id, request);
    }

    @PostMapping("/simulate")
    @Operation(summary = "规则数值仿真（设备距离 → 风险等级 / 建议动作）")
    public Map<String, Object> simulate(@RequestBody SimulateRequest request) {
        demoGuard.requireSimulator();
        return service.simulate(request);
    }

    @PostMapping("/conflict-check")
    @Operation(summary = "规则冲突检查（同区域同类型阈值冲突）")
    public Map<String, List<Map<String, Object>>> conflictCheck(
            @RequestBody(required = false) ConflictCheckRequest request) {
        return Map.of("conflicts", service.conflictCheck());
    }
}
