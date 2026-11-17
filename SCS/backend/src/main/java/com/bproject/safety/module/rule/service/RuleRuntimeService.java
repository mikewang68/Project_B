package com.bproject.safety.module.rule.service;

import com.bproject.safety.common.geo.Polygon2D;
import com.bproject.safety.config.ScsCentralScope;
import com.bproject.safety.config.ScsMachineRegistry;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.alert.model.AlertEvidence;
import com.bproject.safety.module.alert.model.AlertEvidence.PersonnelEvidence;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FencePoint;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * 演示级规则运行时：把已生效的演示规则接到真实 ingest 链路上。
 *
 * <p>不是通用规则 DSL，只识别 3 类演示规则（未戴安全帽 / 防撞距离 / 闯入危险区）。
 * 规则和围栏以只读快照缓存，定时刷新；ingest 在机器请求里执行时不会被
 * {@code ScsScopedJdbcTemplate} 的对象白名单挡住。告警仍走 Alert 主链，
 * 并在 {@code ruleCode/ruleVersion} 上留下版本依据。</p>
 */
@Service
public class RuleRuntimeService {

    private static final Logger log = LoggerFactory.getLogger(RuleRuntimeService.class);
    private static final Pattern NUMBER = Pattern.compile("-?\\d+(?:\\.\\d+)?");
    private static final String DEMO_PREFIX = "SIM-DEMO-SCS-20261113-RULE-";

    private final RuleRepository ruleRepository;
    private final FenceRepository fenceRepository;
    private final AlertService alertService;
    private final Clock clock;
    private final Map<String, String> activeIntrusion = new ConcurrentHashMap<>();

    private volatile List<DemoRule> effectiveRules = List.of();
    private volatile List<DemoFence> effectiveFences = List.of();

    public RuleRuntimeService(RuleRepository ruleRepository, FenceRepository fenceRepository,
                              AlertService alertService, Clock clock) {
        this.ruleRepository = ruleRepository;
        this.fenceRepository = fenceRepository;
        this.alertService = alertService;
        this.clock = clock;
    }

    /** 当前生效规则/围栏快照；调度线程无请求上下文，读取的是服务端权威数据（不做浏览器范围过滤）。 */
    @Scheduled(fixedDelayString = "${scs.rule-runtime.refresh-ms:5000}",
            initialDelayString = "${scs.rule-runtime.initial-delay-ms:5000}")
    public void refresh() {
        try {
            effectiveRules = ruleRepository.findAll().stream()
                    .filter(this::effective)
                    .toList();
            effectiveFences = fenceRepository.findAll().stream()
                    .filter(f -> FenceStatuses.EFFECTIVE.equals(f.statusCode))
                    .toList();
            log.debug("rule runtime refreshed: {} effective rules, {} effective fences",
                    effectiveRules.size(), effectiveFences.size());
        } catch (RuntimeException ex) {
            // 数据库暂时不可用时保留上一版快照，ingest 不因规则刷新失败而中断。
            log.warn("rule runtime refresh failed: {}", ex.getMessage());
        }
    }

    /** 防撞距离规则：命中则返回规则与阈值，供 CollisionService 在告警上写 ruleCode/version。 */
    public Optional<MatchedRule> matchCollision(double distance) {
        return findRule("02", "防撞距离").map(rule -> {
            double threshold = numericParam(rule, "距离", "阈值").orElse(5.4);
            if (distance > threshold) return null;
            return new MatchedRule(rule.id, rule.version, riskOf(rule), threshold, null, null);
        });
    }

    /** AI 安全帽/反光衣规则：按事件类型与置信度阈值匹配；确认时把规则依据写进 Alert。 */
    public Optional<MatchedRule> matchAi(DemoAiEvent event) {
        if (event == null || event.type == null) return Optional.empty();
        String type = event.type;
        if (!(type.contains("安全帽") || type.contains("反光衣") || type.contains("未佩戴"))) {
            return Optional.empty();
        }
        return findRule("01", "未戴安全帽").map(rule -> {
            double threshold = numericParam(rule, "置信", "阈值").orElse(80.0);
            if (threshold > 0 && threshold <= 1) threshold *= 100;
            if (event.confidence < threshold) return null;
            return new MatchedRule(rule.id, rule.version, riskOf(rule), threshold, null, null);
        });
    }

    /**
     * 人员闯入规则：优先按规则的 areas 判定；没有 area 时退回已生效危险围栏的
     * Point-In-Polygon 判定。返回 matched 时由调用方决定是否建单。
     */
    public Optional<MatchedRule> matchIntrusion(DemoPersonnel person, double x, double y, String area) {
        if (person == null) return Optional.empty();
        return findRule("03", "闯入危险区").map(rule -> {
            if (rule.areas != null && !rule.areas.isEmpty()) {
                boolean hit = rule.areas.stream()
                        .anyMatch(a -> "全部区域".equals(a) || a.equals(area));
                return hit ? new MatchedRule(rule.id, rule.version, riskOf(rule), 0, area, null) : null;
            }
            for (DemoFence fence : effectiveFences) {
                if (!"危险区域".equals(fence.kind) && !"预警区域".equals(fence.kind)) continue;
                List<double[]> coords = fence.polygon.stream().map(FencePoint::xy).toList();
                if (Polygon2D.contains(x, y, coords)) {
                    return new MatchedRule(rule.id, rule.version,
                            riskOf(rule), 0, fence.area, fence);
                }
            }
            return null;
        });
    }

    /**
     * 在人员位置 ingest 后调用：命中闯入规则时创建一条带规则来源的 Alert。
     *
     * <p>命名机器来源（UWB 桥）没有 ALERT 对象；这里使用与雷达相同的请求级派生授权，
     * 只允许当前人员对应的 PERSON_INTRUSION 告警。同一人员/规则未离开前不重复建单。</p>
     */
    public void evaluateIntrusion(DemoPersonnel person, double x, double y, String area) {
        if (person == null) return;
        var matched = matchIntrusion(person, x, y, area);
        if (matched.isEmpty()) {
            activeIntrusion.remove(person.id);
            return;
        }
        MatchedRule rule = matched.get();
        String dedup = "PERSON_INTRUSION:" + person.id + ":"
                + (rule.fence() != null ? rule.fence().id : rule.code());
        if (dedup.equals(activeIntrusion.get(person.id))) return;

        var machine = ScsMachineRegistry.current();
        if (machine != null) {
            ScsCentralScope.bindDerivedMachineAuth(
                    ScsCentralScope.Root.PERSONNEL, person.id, ScsCentralScope.Root.ALERT);
        }
        try {
            String zoneName = rule.fence() != null ? rule.fence().name : rule.area();
            String risk = RiskLevels.normalize(rule.riskCode());
            if (risk == null) risk = RiskLevels.WARNING;
            alertService.createRiskAlert(new AlertService.NewRiskAlert(
                    "人员安全", dedup,
                    person.name + "进入" + zoneName, "人员越界", risk,
                    rule.area() == null ? area : rule.area(),
                    person.name + "（" + person.jobNo + "）",
                    rule.code(), rule.version(),
                    DecisionSources.RULE, rule.code(), rule.version(), 0,
                    PersonnelEvidence.of(List.of(new AlertEvidence.Point((int) x, (int) y)),
                            (rule.area() == null ? area : rule.area()) + " · 规则命中",
                            rule.fence() != null ? rule.fence().name : "规则区域",
                            person.braceletId, "在线", "—"),
                    person.name + " 命中规则 " + rule.code() + " v" + rule.version(),
                    "规则区域条件命中，生成人员越界告警"));
            activeIntrusion.put(person.id, dedup);
        } catch (RuntimeException ex) {
            log.warn("rule intrusion alert failed for {}: {}", person.id, ex.getMessage());
        } finally {
            if (machine != null) ScsCentralScope.clearDerivedMachineAuth();
        }
    }

    private Optional<DemoRule> findRule(String demoCodeSuffix, String namePart) {
        String exact = DEMO_PREFIX + demoCodeSuffix;
        return effectiveRules.stream()
                .filter(rule -> exact.equals(rule.id) || (rule.name != null && rule.name.contains(namePart)))
                .findFirst();
    }

    private boolean effective(DemoRule rule) {
        return RuleStatuses.ACTIVE.equals(rule.statusCode) || "EFFECTIVE".equals(rule.statusCode);
    }

    private static String riskOf(DemoRule rule) {
        return rule.riskCode != null ? rule.riskCode : RiskLevels.WARNING;
    }

    private static Optional<Double> numericParam(DemoRule rule, String... labels) {
        if (rule.params == null) return Optional.empty();
        for (DemoRule.Param param : rule.params) {
            if (param == null || param.label() == null || param.value() == null) continue;
            for (String label : labels) {
                if (param.label().contains(label)) {
                    Matcher m = NUMBER.matcher(param.value());
                    if (m.find()) {
                        try {
                            return Optional.of(Double.parseDouble(m.group()));
                        } catch (NumberFormatException ignored) {
                            // fall through to the next parameter
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    /** 一条规则命中后的轻量结果；threshold 仅用于展示/调试。 */
    public record MatchedRule(String code, String version, String riskCode,
                              double threshold, String area, DemoFence fence) {
    }
}
