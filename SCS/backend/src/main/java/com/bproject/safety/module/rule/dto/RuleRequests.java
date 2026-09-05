package com.bproject.safety.module.rule.dto;

import com.bproject.safety.module.rule.model.DemoRule.Param;
import java.util.List;

/** 规则请求 DTO（Backend Demo 轻量表单，不做低代码规则编辑器 / DSL）。 */
public final class RuleRequests {

    private RuleRequests() {
    }

    public record CreateRuleRequest(String name, String category, String type, String scope, List<String> areas,
                                    String risk, String owner, String description, List<String> relatedModules,
                                    List<Param> params, List<String> actions, Boolean highRisk,
                                    Boolean submitReview) {
    }

    public record UpdateRuleRequest(String name, String category, List<String> areas, String risk,
                                    List<Param> params, List<String> actions, Boolean highRisk,
                                    Boolean asNewVersion, Boolean submitReview, String description,
                                    List<String> relatedModules) {
    }

    public record OperatorRequest(String operator, String comment, Boolean confirmHighRisk, String nodeId) {
    }

    public record RollbackRequest(String targetVersion, String operator) {
    }

    public record PublishRequest(String operator, List<String> nodeIds) {
    }

    public record RedeliverRequest(String nodeId, String operator) {
    }

    /** 规则仿真入参，对齐前端 SimulationInput。 */
    public record SimulateRequest(String ruleId, Double distance, Double relSpeed, String direction,
                                  Double radarQuality, String weather) {
    }

    /** 冲突检查入参（可空：默认全量扫描；传入 ruleId 时附带该草稿与现有规则的预检）。 */
    public record ConflictCheckRequest(String ruleId) {
    }
}
