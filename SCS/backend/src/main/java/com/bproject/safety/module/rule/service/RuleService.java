package com.bproject.safety.module.rule.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.rule.dto.RuleRequests.CreateRuleRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.OperatorRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.RedeliverRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.RollbackRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.SimulateRequest;
import com.bproject.safety.module.rule.dto.RuleRequests.UpdateRuleRequest;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.DemoRule.EdgeNode;
import com.bproject.safety.module.rule.model.DemoRule.Param;
import com.bproject.safety.module.rule.model.DemoRule.Version;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import com.bproject.safety.module.rule.repository.RuleRepository.RuleQuery;
import java.time.Clock;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 规则配置业务服务（Backend Demo）：CRUD、版本历史、状态流转、发布（边缘同步）、
 * 版本异常 / 重新下发、回滚（生成新版本再走发布）、冲突检查与数值仿真。
 *
 * <p>不实现规则引擎 / DSL / Drools；实时人员越界 / 碰撞判定仍由既有 Demo 逻辑负责，
 * 本服务只管理规则生命周期。非法状态流转统一抛 409 STATE_CONFLICT。</p>
 */
@Service
public class RuleService {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final List<String> EDGE_IDS = List.of("EDGE-01", "EDGE-02", "EDGE-03", "EDGE-04");
    private static final String DEFAULT_APPROVER = "安全总监 赵民";

    private final RuleRepository repository;
    private final DomainLivePublisher publisher;
    private final Clock clock;

    public RuleService(RuleRepository repository, DomainLivePublisher publisher, Clock clock) {
        this.repository = repository;
        this.publisher = publisher;
        this.clock = clock;
    }

    // ---------- 查询 ----------

    public List<DemoRule> list(String keyword, String category, String status, String risk) {
        return repository.filter(new RuleQuery(keyword, normalize(category), normalize(status), normalize(risk)));
    }

    public DemoRule get(String id) {
        return require(id);
    }

    public Map<String, Object> metrics() {
        List<DemoRule> all = repository.findAll();
        String today = nowDate();
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("active", all.stream().filter(r -> RuleStatuses.ACTIVE.equals(r.status)).count());
        m.put("review", all.stream().filter(r -> RuleStatuses.REVIEW.equals(r.status)).count());
        m.put("draft", all.stream().filter(r -> RuleStatuses.DRAFT.equals(r.status)).count());
        m.put("mismatch", all.stream().filter(r -> RuleStatuses.MISMATCH.equals(r.status)).count());
        m.put("changedToday", all.stream().filter(r -> r.updatedAt != null && r.updatedAt.startsWith(today)).count());
        return m;
    }

    public List<Version> versions(String id) {
        return require(id).versions;
    }

    // ---------- 新建 / 编辑 ----------

    public DemoRule create(CreateRuleRequest req) {
        if (req == null || isBlank(req.name())) {
            throw ApiException.unprocessable("规则名称不能为空");
        }
        DemoRule r = new DemoRule();
        r.id = nextRuleId(req.category());
        r.name = req.name();
        r.category = firstNonBlank(req.category(), "人员安全");
        r.type = req.type();
        r.scope = req.scope();
        r.areas = req.areas() == null ? new ArrayList<>() : new ArrayList<>(req.areas());
        r.version = "v1.0";
        r.platformVersion = "v1.0";
        r.risk = firstNonBlank(req.risk(), "一般");
        r.owner = firstNonBlank(req.owner(), "安全员 李娜");
        r.approver = bool(req.submitReview()) ? "待审批" : "—";
        r.effectiveAt = "—";
        r.description = firstNonBlank(req.description(), "人工新建规则（Backend Demo）");
        r.relatedModules = req.relatedModules() == null ? List.of("告警中心") : new ArrayList<>(req.relatedModules());
        r.params = req.params() == null ? new ArrayList<>() : new ArrayList<>(req.params());
        r.actions = req.actions() == null ? new ArrayList<>() : new ArrayList<>(req.actions());
        r.highRisk = bool(req.highRisk());
        r.status = bool(req.submitReview()) ? RuleStatuses.REVIEW : RuleStatuses.DRAFT;
        r.updatedAt = nowDateTime();
        r.versions = new ArrayList<>(List.of(new Version(r.version, nowDate(),
                bool(req.submitReview()) ? "新建并提交评审" : "新建草稿", r.owner, "当前", null)));
        r.edgeNodes = placeholderNodes();
        snapshot(r, r.version);
        repository.save(r);
        publishChanged(r);
        return get(r.id);
    }

    public DemoRule update(String id, UpdateRuleRequest req) {
        DemoRule r = require(id);
        boolean asNew = bool(req.asNewVersion());
        if (asNew) {
            // 已生效规则“新建版本”：不覆盖历史，版本号 minor +1，回到草稿 / 待评审
            String oldVersion = r.version;
            String newVersion = bumpMinor(oldVersion);
            r.versions = prependVersion(r, newVersion,
                    bool(req.submitReview()) ? "基于上一版本修订，提交评审" : "新版本草稿", null);
            r.version = newVersion;
            r.platformVersion = newVersion;
            r.sourceVersion = null;
            snapshot(r, newVersion);
        }
        if (req.name() != null) {
            r.name = req.name();
        }
        if (req.category() != null) {
            r.category = req.category();
        }
        if (req.areas() != null) {
            r.areas = new ArrayList<>(req.areas());
        }
        if (req.risk() != null) {
            r.risk = req.risk();
        }
        if (req.params() != null) {
            r.params = new ArrayList<>(req.params());
        }
        if (req.actions() != null) {
            r.actions = new ArrayList<>(req.actions());
        }
        if (req.highRisk() != null) {
            r.highRisk = req.highRisk();
        }
        if (req.description() != null) {
            r.description = req.description();
        }
        if (req.relatedModules() != null) {
            r.relatedModules = new ArrayList<>(req.relatedModules());
        }
        r.status = bool(req.submitReview()) ? RuleStatuses.REVIEW : RuleStatuses.DRAFT;
        r.approver = bool(req.submitReview()) ? "待审批" : "—";
        r.effectiveAt = "—";
        r.updatedAt = nowDateTime();
        repository.save(r);
        publishChanged(r);
        return get(id);
    }

    // ---------- 状态流转 ----------

    public DemoRule submit(String id, OperatorRequest req) {
        DemoRule r = require(id);
        requireStatus(r, RuleStatuses.DRAFT);
        if (r.highRisk && !bool(req == null ? null : req.confirmHighRisk())) {
            throw ApiException.unprocessable("高危规则提交评审需显式二次确认（confirmHighRisk=true）");
        }
        r.status = RuleStatuses.REVIEW;
        r.approver = "待审批";
        r.updatedAt = nowDateTime();
        repository.save(r);
        publishChanged(r);
        return get(id);
    }

    public DemoRule approve(String id, OperatorRequest req) {
        DemoRule r = require(id);
        requireStatus(r, RuleStatuses.REVIEW);
        if (r.highRisk && !bool(req == null ? null : req.confirmHighRisk())) {
            throw ApiException.unprocessable("高危安全参数批准需显式二次确认（confirmHighRisk=true）");
        }
        r.status = RuleStatuses.APPROVED;
        r.approver = DEFAULT_APPROVER;
        r.updatedAt = nowDateTime();
        repository.save(r);
        publishChanged(r);
        return get(id);
    }

    public DemoRule reject(String id, OperatorRequest req) {
        DemoRule r = require(id);
        requireStatus(r, RuleStatuses.REVIEW);
        r.status = RuleStatuses.DRAFT;
        r.approver = "—";
        String comment = req != null && !isBlank(req.comment()) ? "（驳回原因：" + req.comment() + "）" : "";
        r.versions = prependVersion(r, r.version, "评审驳回，退回草稿" + comment, null);
        r.updatedAt = nowDateTime();
        repository.save(r);
        publishChanged(r);
        return get(id);
    }

    public DemoRule publish(String id, com.bproject.safety.module.rule.dto.RuleRequests.PublishRequest req) {
        DemoRule r = require(id);
        requireStatus(r, RuleStatuses.APPROVED);
        // 模拟 EDGE-01~04 同步：全部成功后规则生效（不连接真实边缘节点）
        r.status = RuleStatuses.PUBLISHING;
        repository.save(r);
        r.edgeNodes = syncedNodes(r.version);
        r.status = RuleStatuses.ACTIVE;
        r.effectiveAt = nowDateTime();
        r.updatedAt = nowDateTime();
        repository.save(r);
        publisher.publish(LiveEventTypes.RULE_PUBLISHED, syncData(r));
        return get(id);
    }

    public DemoRule redeliver(String id, RedeliverRequest req) {
        DemoRule r = require(id);
        String nodeId = req == null ? null : req.nodeId();
        List<EdgeNode> nodes = new ArrayList<>();
        for (EdgeNode n : r.edgeNodes) {
            if (nodeId == null || nodeId.isBlank() || nodeId.equals(n.node())) {
                nodes.add(new EdgeNode(n.node(), r.version, RuleStatuses.EDGE_SYNCED));
            } else {
                nodes.add(n);
            }
        }
        // 未指定节点时（全量重下发）兜底补齐 4 个节点
        if (nodeId == null || nodeId.isBlank()) {
            nodes = syncedNodes(r.version);
        }
        r.edgeNodes = nodes;
        boolean allSynced = r.edgeNodes.size() == EDGE_IDS.size()
                && r.edgeNodes.stream().allMatch(n -> RuleStatuses.EDGE_SYNCED.equals(n.state()));
        if (allSynced && RuleStatuses.MISMATCH.equals(r.status)) {
            r.status = RuleStatuses.ACTIVE;
            r.effectiveAt = nowDateTime();
        }
        r.updatedAt = nowDateTime();
        repository.save(r);
        publisher.publish(LiveEventTypes.RULE_SYNC_CHANGED, syncData(r));
        return get(id);
    }

    public Map<String, Object> rollback(String id, RollbackRequest req) {
        DemoRule r = require(id);
        if (req == null || isBlank(req.targetVersion())) {
            throw ApiException.unprocessable("回滚必须指定目标历史版本");
        }
        String target = req.targetVersion();
        Version historical = r.versions.stream().filter(v -> v.version().equals(target)).findFirst()
                .orElseThrow(() -> ApiException.unprocessable("历史版本不存在: " + target));
        // 关键：不覆盖当前版本，而是基于目标版本生成一个新版本，重新走评审 → 发布
        String newVersion = bumpMinor(r.version);
        r.versions = prependVersion(r, newVersion, "基于 " + target + " 回滚生成，待评审", null);
        // 若保留了目标版本参数快照则还原参数（Demo）
        if (r.versionSnapshots != null && r.versionSnapshots.get(target) != null) {
            r.params = new ArrayList<>(r.versionSnapshots.get(target));
        }
        r.version = newVersion;
        r.platformVersion = newVersion;
        r.sourceVersion = target;
        r.status = RuleStatuses.REVIEW;
        r.approver = "待审批";
        r.effectiveAt = "—";
        r.updatedAt = nowDateTime();
        snapshot(r, newVersion);
        repository.save(r);
        publishChanged(r);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("newVersion", newVersion);
        out.put("sourceVersion", target);
        out.put("status", r.status);
        out.put("rule", get(id));
        out.put("versions", r.versions);
        out.put("basedOn", historical);
        return out;
    }

    public DemoRule disable(String id, OperatorRequest req) {
        DemoRule r = require(id);
        if (!List.of(RuleStatuses.ACTIVE, RuleStatuses.MISMATCH, RuleStatuses.APPROVED).contains(r.status)) {
            throw ApiException.conflict("当前状态不允许停用（当前状态：" + r.status + "）");
        }
        r.status = RuleStatuses.DISABLED;
        r.updatedAt = nowDateTime();
        repository.save(r);
        publishChanged(r);
        return get(id);
    }

    /** 模拟 EDGE-03 落后一个版本 → 规则进入版本异常（SIMULATED 演示器）。 */
    public DemoRule simulateMismatch(String id, OperatorRequest req) {
        DemoRule r = require(id);
        String staleVersion = r.versions.size() > 1 ? r.versions.get(1).version() : prevMinor(r.version);
        List<EdgeNode> nodes = new ArrayList<>();
        for (String node : EDGE_IDS) {
            if ("EDGE-03".equals(node)) {
                nodes.add(new EdgeNode(node, staleVersion, RuleStatuses.EDGE_MISMATCH));
            } else {
                nodes.add(new EdgeNode(node, r.version, RuleStatuses.EDGE_SYNCED));
            }
        }
        r.edgeNodes = nodes;
        r.status = RuleStatuses.MISMATCH;
        r.updatedAt = nowDateTime();
        repository.save(r);
        publisher.publish(LiveEventTypes.RULE_SYNC_CHANGED, syncData(r));
        return get(id);
    }

    // ---------- 仿真 / 冲突 ----------

    /** 设备距离规则数值仿真（复用前端 mock 阈值算法，不做真正规则引擎）。 */
    public Map<String, Object> simulate(SimulateRequest req) {
        double distance = req != null && req.distance() != null ? req.distance() : 8.0;
        String weather = req != null && req.weather() != null ? req.weather() : "晴";
        double factor = switch (weather) {
            case "小雨" -> 1.15;
            case "雾天" -> 1.3;
            default -> 1.0;
        };
        double warn = 10 * factor;
        double severe = 6 * factor;
        double emergency = 3 * factor;
        String level;
        List<String> actions;
        if (distance <= emergency) {
            level = "紧急风险";
            actions = List.of("设备停机", "PLC 联动", "通知司机", "通知安全员");
        } else if (distance <= severe) {
            level = "严重风险";
            actions = List.of("减速请求", "通知司机", "通知安全员");
        } else if (distance <= warn) {
            level = "预警风险";
            actions = List.of("通知司机", "现场声光提醒");
        } else {
            level = "安全";
            actions = List.of("保持监测");
        }
        DemoRule matched = req != null && !isBlank(req.ruleId())
                ? repository.findById(req.ruleId()).orElse(null) : null;
        String matchedRule = matched != null ? matched.id + " " + matched.version : "RULE-DEV-003 v2.4";
        String thresholdNote = "晴".equals(weather)
                ? String.format("按%s标准阈值：预警 %.1fm / 严重 %.1fm / 紧急 %.1fm",
                        matched != null ? matched.version : "v2.4", warn, severe, emergency)
                : String.format("%s天气修正 ×%.2f：预警 %.1fm / 严重 %.1fm / 紧急 %.1fm",
                        weather, factor, warn, severe, emergency);
        String escalation;
        if (distance > emergency && distance - emergency < 2) {
            escalation = String.format("距离继续下降至 %.1fm 内，预计升级为紧急风险并触发设备停机", emergency);
        } else if ("安全".equals(level)) {
            escalation = "距离继续缩小至预警阈值内将触发预警风险";
        } else {
            escalation = "若相对速度持续增大，升级时间将进一步缩短";
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("level", level);
        out.put("matchedRule", matchedRule);
        out.put("thresholdNote", thresholdNote);
        out.put("actions", actions);
        out.put("escalation", escalation);
        return out;
    }

    /**
     * 冲突检查（Demo 简单判定）：同属“设备安全”、适用区域存在重叠、且同名数值参数（如预警距离）取值不同
     * 即构成冲突；高危规则冲突 severity=high / blocking，不允许前端直接忽略。
     */
    public List<Map<String, Object>> conflictCheck() {
        List<DemoRule> all = repository.findAll();
        List<DemoRule> dev = all.stream().filter(r -> "设备安全".equals(r.category)).toList();
        List<Map<String, Object>> conflicts = new ArrayList<>();
        for (int i = 0; i < dev.size(); i++) {
            for (int j = i + 1; j < dev.size(); j++) {
                DemoRule a = dev.get(i);
                DemoRule b = dev.get(j);
                String overlap = firstOverlapArea(a, b);
                if (overlap == null) {
                    continue;
                }
                String[] pair = findMismatchParam(a, b);
                if (pair == null) {
                    continue;
                }
                boolean high = a.highRisk || b.highRisk;
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("ruleA", a.id);
                c.put("ruleB", b.id);
                c.put("ruleAName", a.name);
                c.put("ruleBName", b.name);
                c.put("area", overlap);
                c.put("deviceKind", "转运车辆");
                c.put("paramLabel", pair[0]);
                c.put("field", pair[0]);
                c.put("valueA", pair[1]);
                c.put("valueB", pair[2]);
                c.put("highRisk", high);
                c.put("blocking", high);
                c.put("severity", high ? "high" : "normal");
                c.put("desc", a.id + " 与 " + b.id + " 在「" + overlap + "」的「" + pair[0]
                        + "」阈值不一致（" + pair[1] + " vs " + pair[2] + "）");
                conflicts.add(c);
            }
        }
        return conflicts;
    }

    // ---------- 内部辅助 ----------

    private String[] findMismatchParam(DemoRule a, DemoRule b) {
        for (Param pa : a.params) {
            for (Param pb : b.params) {
                if (pa.label().equals(pb.label()) && !pa.value().equals(pb.value())
                        && pa.label().contains("距离")) {
                    return new String[] {pa.label(), pa.value(), pb.value()};
                }
            }
        }
        return null;
    }

    private String firstOverlapArea(DemoRule a, DemoRule b) {
        if (a.areas.contains("全部区域") || b.areas.contains("全部区域")) {
            return a.areas.contains("全部区域") ? firstArea(b.areas) : firstArea(a.areas);
        }
        return a.areas.stream().filter(b.areas::contains).findFirst().orElse(null);
    }

    private String firstArea(List<String> areas) {
        return areas == null || areas.isEmpty() ? "全部区域" : areas.get(0);
    }

    private DemoRule require(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("规则不存在: " + id));
    }

    private void requireStatus(DemoRule r, String expected) {
        if (!expected.equals(r.status)) {
            throw ApiException.conflict("当前状态不允许执行该操作（当前状态：" + r.status + "）");
        }
    }

    private void publishChanged(DemoRule r) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", r.id);
        data.put("version", r.version);
        data.put("status", r.status);
        publisher.publish(LiveEventTypes.RULE_CHANGED, data);
    }

    private Map<String, Object> syncData(DemoRule r) {
        long synced = r.edgeNodes.stream().filter(n -> RuleStatuses.EDGE_SYNCED.equals(n.state())).count();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("ruleId", r.id);
        data.put("version", r.version);
        data.put("status", r.status);
        data.put("synced", synced);
        data.put("total", r.edgeNodes.size());
        return data;
    }

    private List<Version> prependVersion(DemoRule r, String version, String note, List<DemoRule.VersionDiff> diffs) {
        List<Version> out = new ArrayList<>();
        out.add(new Version(version, nowDate(), note, shortAuthor(r.owner), "当前", diffs));
        r.versions.forEach(v -> out.add(new Version(v.version(), v.date(), v.note(), v.author(),
                "历史版本", v.diffs())));
        return out;
    }

    private void snapshot(DemoRule r, String version) {
        if (r.versionSnapshots == null) {
            r.versionSnapshots = new LinkedHashMap<>();
        }
        r.versionSnapshots.put(version, new ArrayList<>(r.params));
    }

    private List<EdgeNode> placeholderNodes() {
        return EDGE_IDS.stream().map(n -> new EdgeNode(n, "—", RuleStatuses.EDGE_SYNCED)).toList();
    }

    private List<EdgeNode> syncedNodes(String version) {
        return EDGE_IDS.stream().map(n -> new EdgeNode(n, version, RuleStatuses.EDGE_SYNCED)).toList();
    }

    private String nextRuleId(String category) {
        String prefix = switch (firstNonBlank(category, "人员安全")) {
            case "设备安全" -> "RULE-DEV";
            case "AI识别" -> "RULE-AI";
            case "告警策略" -> "RULE-ALM";
            case "联动策略" -> "RULE-LNK";
            case "通知策略" -> "RULE-NTF";
            default -> "RULE-PER";
        };
        long n = repository.findAll().stream().filter(r -> r.id != null && r.id.startsWith(prefix)).count() + 1;
        return String.format("%s-%03d", prefix, n);
    }

    /** v3.3 → v3.4（minor +1，Demo 语义化简化）。 */
    static String bumpMinor(String version) {
        String[] parts = version.replace("v", "").split("\\.");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 1;
        int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        return "v" + major + "." + (minor + 1);
    }

    private String prevMinor(String version) {
        String[] parts = version.replace("v", "").split("\\.");
        int major = parts.length > 0 ? Integer.parseInt(parts[0]) : 1;
        int minor = Math.max(0, (parts.length > 1 ? Integer.parseInt(parts[1]) : 0) - 1);
        return "v" + major + "." + minor;
    }

    private String shortAuthor(String owner) {
        if (owner == null) {
            return "当前用户";
        }
        return owner.replaceAll("^安全员|^设备管理员|^调度员", "").trim().isEmpty()
                ? owner : owner.replaceAll("^安全员|^设备管理员|^调度员", "").trim();
    }

    private String nowDateTime() {
        return java.time.LocalDateTime.now(clock.withZone(ZONE)).format(DT);
    }

    private String nowDate() {
        return java.time.LocalDate.now(clock.withZone(ZONE)).format(D);
    }

    private static boolean bool(Boolean b) {
        return b != null && b;
    }

    private static String normalize(String s) {
        return s == null || s.isBlank() || "全部".equals(s) ? null : s;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) {
            if (!isBlank(v)) {
                return v;
            }
        }
        return null;
    }
}
