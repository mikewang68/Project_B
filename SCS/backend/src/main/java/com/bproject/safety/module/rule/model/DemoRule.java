package com.bproject.safety.module.rule.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * 规则配置 Demo 实体（Backend Demo 轻量模型，不做正式规则引擎 / DSL）。
 *
 * <p>字段形状对齐前端 frontend/src/types/rule.ts 的 SafetyRule，保证前端移除 Mock 后视觉不变。
 * params / actions 使用结构化 DTO，不做规则 DSL。</p>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class DemoRule {

    public String id;
    public String name;
    public String category;
    /** 规则类型（比 category 更细的判定类型，Demo 可空）。 */
    public String type;
    /** 适用范围描述（Demo 可空）。 */
    public String scope;
    public List<String> areas = new ArrayList<>();
    public String version;
    public String platformVersion;
    public String risk;
    public String status;
    public String updatedAt;
    public String owner;
    public String approver;
    public String effectiveAt;
    public List<Param> params = new ArrayList<>();
    public List<String> actions = new ArrayList<>();
    public boolean highRisk;
    public List<String> relatedModules = new ArrayList<>();
    public String description;
    public List<Version> versions = new ArrayList<>();
    public List<EdgeNode> edgeNodes = new ArrayList<>();
    /** 回滚来源版本（仅由回滚生成的新版本携带，Demo 审计用）。 */
    public String sourceVersion;
    /** 版本 → 参数快照（Backend Demo 回滚还原用；前端不消费该字段）。 */
    public java.util.Map<String, List<Param>> versionSnapshots;

    public DemoRule copy() {
        DemoRule r = new DemoRule();
        r.id = id;
        r.name = name;
        r.category = category;
        r.type = type;
        r.scope = scope;
        r.areas = areas == null ? new ArrayList<>() : new ArrayList<>(areas);
        r.version = version;
        r.platformVersion = platformVersion;
        r.risk = risk;
        r.status = status;
        r.updatedAt = updatedAt;
        r.owner = owner;
        r.approver = approver;
        r.effectiveAt = effectiveAt;
        r.params = params == null ? new ArrayList<>() : new ArrayList<>(params);
        r.actions = actions == null ? new ArrayList<>() : new ArrayList<>(actions);
        r.highRisk = highRisk;
        r.relatedModules = relatedModules == null ? new ArrayList<>() : new ArrayList<>(relatedModules);
        r.description = description;
        r.versions = versions == null ? new ArrayList<>() : new ArrayList<>(versions);
        r.edgeNodes = edgeNodes == null ? new ArrayList<>() : new ArrayList<>(edgeNodes);
        r.sourceVersion = sourceVersion;
        r.versionSnapshots = versionSnapshots == null ? null
                : new java.util.LinkedHashMap<>(versionSnapshots);
        return r;
    }

    /** 规则参数项，对齐前端 RuleParam。 */
    public record Param(String label, String value, Boolean danger, String hint) {
        public static Param of(String label, String value) {
            return new Param(label, value, null, null);
        }

        public static Param danger(String label, String value) {
            return new Param(label, value, Boolean.TRUE, null);
        }

        public static Param hint(String label, String value, String hint) {
            return new Param(label, value, null, hint);
        }
    }

    /** 版本差异项，对齐前端 VersionDiff。 */
    public record VersionDiff(String label, String from, String to) {
    }

    /** 规则历史版本，对齐前端 RuleVersion。 */
    public record Version(String version, String date, String note, String author, String state,
                          List<VersionDiff> diffs) {
    }

    /** 边缘节点同步状态，对齐前端 EdgeNodeState。 */
    public record EdgeNode(String node, String version, String state) {
    }
}
