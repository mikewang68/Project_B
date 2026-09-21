package com.bproject.safety.module.collision.service;

import com.bproject.safety.common.error.ApiException;
import com.bproject.safety.common.realtime.DomainLivePublisher;
import com.bproject.safety.common.realtime.LiveEventGate;
import com.bproject.safety.common.realtime.LiveEventTypes;
import com.bproject.safety.module.alert.dto.AlertRequests.LinkageRequest;
import com.bproject.safety.module.alert.dto.AlertRequests.TakeoverRequest;
import com.bproject.safety.module.alert.model.AlertEvidence.CollisionEvidence;
import com.bproject.safety.module.alert.model.DecisionSources;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.model.RiskLevels;
import com.bproject.safety.module.alert.service.AlertService;
import com.bproject.safety.module.collision.model.CollisionRiskLevels;
import com.bproject.safety.module.collision.model.CollisionStep;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.model.DistancePoint;
import com.bproject.safety.module.collision.model.PairState;
import com.bproject.safety.module.collision.model.SensorHealth;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import jakarta.annotation.PostConstruct;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 设备防碰撞业务服务（Backend Demo）：设备台账、配对距离、风险计算、接近模拟、联动、人工接管。
 *
 * <p>风险阈值沿用前端旧 Mock：≥10m 安全 / 6~10m 预警 / 3~6m 严重 / &lt;3m 紧急（雷达断数为待确认）。
 * 严重首次经 {@link AlertService#createRiskAlert} 建单，紧急升级同一 Alert（去重键 COLLISION:a:b），
 * 不建立第二套碰撞告警库；联动 / 接管复用 Alert 主链能力，三端共享同一 Timeline。</p>
 */
@Service
public class CollisionService {

    private static final double[] APPROACH_SEQUENCE = {9.2, 7.1, 5.4, 3.8, 2.8};
    private static final double INIT_DISTANCE = 12.6;

    private final CollisionRepository repository;
    private final AlertService alertService;
    private final DomainLivePublisher publisher;
    private final Clock clock;
    private final LiveEventGate gate;
    private final Map<String, PairState> pairs = new LinkedHashMap<>();

    public CollisionService(CollisionRepository repository, AlertService alertService,
                            DomainLivePublisher publisher, Clock clock, LiveEventGate gate) {
        this.repository = repository;
        this.alertService = alertService;
        this.publisher = publisher;
        this.clock = clock;
        this.gate = gate;
    }

    @PostConstruct
    void initPairs() {
        resetAllPairs();
    }

    /** 重置全部配对运行态（测试隔离 / 演示复位用，不删除已生成的 Alert）。 */
    public synchronized void resetAllPairs() {
        pairs.clear();
        pairs.put("VEH-07", new PairState("VEH-07", "TIP-02", INIT_DISTANCE, initialTrend(INIT_DISTANCE)));
        pairs.put("VEH-08", new PairState("VEH-08", "CRANE-01", 14.0, initialTrend(14.0)));
    }

    private List<DistancePoint> initialTrend(double end) {
        double[] seed = {15.4, 14.8, 15.1, 14.4, 13.9, 14.2, 13.5, 13.1, 12.9};
        List<DistancePoint> points = new ArrayList<>();
        int base = -27;
        for (double v : seed) {
            points.add(new DistancePoint(base + "s", v));
            base += 3;
        }
        points.add(new DistancePoint("现在", end));
        return points;
    }

    // ---------- 查询 ----------

    public List<DemoCollisionDevice> devices(String type) {
        return repository.findAll().stream()
                .filter(d -> type == null || type.isBlank() || type.equals(d.type))
                .map(this::decorate).toList();
    }

    public DemoCollisionDevice get(String id) {
        return decorate(requireDevice(id));
    }

    public PairView pair(String currentId, String relatedId) {
        PairState pair = resolvePair(currentId != null ? currentId : relatedId);
        return PairView.of(pair, OffsetDateTime.now(clock).toString());
    }

    public List<DistancePoint> trend(String id) {
        return resolvePair(id).trend;
    }

    private DemoCollisionDevice decorate(DemoCollisionDevice d) {
        PairState pair = pairOfDevice(d.id);
        if (pair == null) {
            return d;
        }
        d.riskCode = pair.riskCode;
        d.healthCode = pair.healthCode;
        d.radarStatus = pair.radarDown ? "数据中断" : "正常";
        if (pair.deviceStopped && d.id.equals(pair.currentId)) {
            d.status = "已停止";
            d.speed = 0;
        }
        d.controlStatus = pair.controlFailure ? "状态未确认"
                : pair.deviceStopped ? "停机已确认" : d.controlStatus;
        d.latestAlertId = pair.activeAlertId;
        if (pair.activeAlertId != null) {
            d.latestAlert = "未处理碰撞告警 " + pair.activeAlertId;
        }
        return d;
    }

    // ---------- 模拟器（SIMULATED） ----------

    @Transactional
    public synchronized SimulateResult simulate(String deviceId, String scenario) {
        PairState pair = resolvePair(deviceId);
        String s = scenario == null ? "approach" : scenario;
        // Phase B：建单 / 升级 / 设备 save 全部完成后再统一广播（含中途的 risk_changed）。
        gate.buffer(() -> {
            switch (s) {
                case "approach" -> advanceApproach(pair);
                case "radarDown" -> toggleRadar(pair, true);
                case "radarRecover", "reset" -> resetPair(pair);
                case "linkageFail" -> linkageFail(pair);
                default -> throw ApiException.unprocessable("不支持的模拟场景: " + s);
            }
            broadcastPair(pair, "collision.changed");
        });
        return SimulateResult.of(pair, decorate(requireDevice(pair.currentId)),
                decorate(requireDevice(pair.relatedId)));
    }

    private void advanceApproach(PairState pair) {
        if (pair.radarDown) {
            resetPair(pair);
        }
        if (pair.approachIndex >= APPROACH_SEQUENCE.length) {
            pair.approachIndex = 0;
            resetPair(pair);
        }
        double value = APPROACH_SEQUENCE[pair.approachIndex++];
        applyDistance(pair, value);
    }

    private void applyDistance(PairState pair, double value) {
        String before = pair.riskCode;
        pair.distance = value;
        pair.relSpeed = 1.8;
        pair.trend = new ArrayList<>(pair.trend.subList(Math.max(0, pair.trend.size() - 13), pair.trend.size()));
        pair.trend.add(new DistancePoint("现在", value));
        pair.riskCode = riskCodeOf(value);
        pair.healthCode = SensorHealth.NORMAL;
        pair.updatedAt = OffsetDateTime.now(clock);

        if (value <= 9.2) {
            step(pair, "detect", "success", "识别到设备持续接近");
            step(pair, "alarm", "success", "现场声光提醒已触发");
            step(pair, "driver", "success", "司机终端已收到提醒");
        }
        if (value <= 5.4) {
            step(pair, "slow", "success", "减速请求已发送");
            ensureCollisionAlert(pair, CollisionRiskLevels.SEVERE);
        }
        if (value < 3) {
            step(pair, "stop", "running", "正在发送紧急停机指令");
            ensureCollisionAlert(pair, CollisionRiskLevels.SEVERE);
            if (pair.activeAlertId != null) {
                DemoAlert upgraded = alertService.upgradeRisk(pair.activeAlertId, RiskLevels.URGENT,
                        "相对距离降至 " + value + "m，达到紧急碰撞阈值");
                pair.activeAlertId = upgraded.id;
            }
        }
        if (!before.equals(pair.riskCode)) {
            broadcastPair(pair, LiveEventTypes.COLLISION_RISK_CHANGED);
        }
    }

    /** 严重风险首次建单；同一设备对存在未关闭告警时复用（去重），紧急阶段只升级不新建。 */
    private void ensureCollisionAlert(PairState pair, String collisionRiskCode) {
        DemoAlert open = alertService.findOpenByDedupKey(dedupKey(pair));
        if (open != null) {
            pair.activeAlertId = open.id;
            return;
        }
        if (pair.activeAlertId != null) {
            pair.activeAlertId = null;
        }
        DemoCollisionDevice cur = repository.findById(pair.currentId).orElseThrow();
        DemoCollisionDevice rel = repository.findById(pair.relatedId).orElseThrow();
        String alertRiskCode = CollisionRiskLevels.toAlertRiskCode(collisionRiskCode);
        // 防碰撞阈值判定：provenance 标记 COLLISION，不伪造 SafetyRule 版本（F-07）。
        DemoAlert alert = alertService.createRiskAlert(new AlertService.NewRiskAlert(
                "设备防碰撞", dedupKey(pair),
                cur.name + "距离" + rel.name + "过近", "设备距离风险", alertRiskCode,
                firstArea(cur.area, rel.area), cur.name + " / " + rel.name,
                null, null, DecisionSources.COLLISION, null, null, 0,
                CollisionEvidence.of(pair.distance, pair.relSpeed,
                        pair.trend.stream().map(DistancePoint::value).toList(),
                        pair.radarDown ? "数据中断" : "正常", "预测制动距离 2.4m"),
                "毫米波雷达检测到设备持续接近，当前距离 " + pair.distance + "m",
                "防碰撞风险计算命中" + CollisionRiskLevels.label(collisionRiskCode) + "阈值，生成设备防碰撞告警"));
        pair.activeAlertId = alert.id;
        // Phase B：显式持久化当前设备的 latestAlertId（copy-on-write 后不再有内部引用魔法）。
        cur.latestAlertId = alert.id;
        repository.save(cur);
    }

    private void toggleRadar(PairState pair, boolean down) {
        pair.radarDown = down;
        pair.radarQuality = down ? 0 : 97;
        // “待确认”是感知健康态，不再写入风险等级（F-01 语义拆分）。
        pair.healthCode = down ? SensorHealth.UNCERTAIN : SensorHealth.NORMAL;
        if (!down) {
            pair.riskCode = riskCodeOf(pair.distance);
        }
        if (down) {
            step(pair, "detect", "failed", "雷达数据中断，无法确认安全距离");
            pair.plcStatus = "保守限制运行";
        }
    }

    private void resetPair(PairState pair) {
        pair.distance = INIT_DISTANCE;
        pair.relSpeed = 1.2;
        pair.radarQuality = 97;
        pair.radarDown = false;
        pair.deviceStopped = false;
        pair.controlFailure = false;
        pair.approachIndex = 0;
        pair.plcStatus = "待命";
        pair.riskCode = CollisionRiskLevels.SAFE;
        pair.healthCode = SensorHealth.NORMAL;
        pair.steps = PairState.baseSteps();
        pair.trend = initialTrend(INIT_DISTANCE);
        pair.activeAlertId = null;
        pair.updatedAt = OffsetDateTime.now(clock);
    }

    // ---------- 联动 ----------

    @Transactional
    public synchronized List<CollisionStep> linkage(String deviceId, String mode) {
        PairState pair = resolvePair(deviceId);
        boolean fail = "fail".equalsIgnoreCase(mode);
        // Phase B：Alert 联动写 + 广播在缓冲区内，全部 save 成功后统一发布。
        gate.buffer(() -> {
            if (!fail) {
                for (String id : List.of("detect", "alarm", "driver", "slow", "stop", "plc")) {
                    step(pair, id, "success", successDetail(id));
                }
                pair.deviceStopped = true;
                pair.controlFailure = false;
                pair.plcStatus = "PLC 已确认停机";
                if (pair.activeAlertId != null) {
                    alertService.linkage(pair.activeAlertId, new LinkageRequest("success"), null);
                }
            } else {
                linkageFail(pair);
            }
            broadcastPair(pair, LiveEventTypes.COLLISION_LINKAGE_CHANGED);
        });
        return pair.steps;
    }

    private void linkageFail(PairState pair) {
        pair.distance = 2.8;
        pair.riskCode = CollisionRiskLevels.URGENT;
        ensureCollisionAlert(pair, CollisionRiskLevels.SEVERE);
        if (pair.activeAlertId != null) {
            DemoAlert a = alertService.findOpenByDedupKey(dedupKey(pair));
            if (a != null && !RiskLevels.URGENT.equals(a.riskCode)) {
                DemoAlert up = alertService.upgradeRisk(a.id, RiskLevels.URGENT, "联动失败前距离持续下降");
                pair.activeAlertId = up.id;
            }
        }
        for (String id : List.of("detect", "alarm", "driver", "slow", "stop")) {
            step(pair, id, "success", id.equals("stop") ? "停机指令已发送" : "安全动作已完成");
        }
        step(pair, "plc", "failed", "PLC 回执超时");
        pair.controlFailure = true;
        pair.plcStatus = "PLC 回执超时";
        if (pair.activeAlertId != null) {
            alertService.linkage(pair.activeAlertId, new LinkageRequest("fail"), null);
        }
    }

    // ---------- 人工接管 / 解除申请（复用 Alert 主链） ----------

    @Transactional
    public synchronized TakeoverResult takeover(String deviceId, String operator, String note) {
        PairState pair = resolvePair(deviceId);
        // Phase B：Alert 接管写 + 广播缓冲，save 全部成功后统一发布。
        gate.buffer(() -> {
            if (pair.activeAlertId != null) {
                alertService.takeover(pair.activeAlertId,
                        new TakeoverRequest(operator, "PLC 回执超时，现场人工确认停机", note, "设备已停止"), null);
            }
            pair.controlFailure = false;
            pair.deviceStopped = true;
            pair.plcStatus = "人工确认停机";
            step(pair, "plc", "success", "人工急停已现场确认");
            broadcastPair(pair, LiveEventTypes.COLLISION_LINKAGE_CHANGED);
        });
        return new TakeoverResult("人工确认停机", "stopped",
                OffsetDateTime.now(clock).toString(), pair.activeAlertId);
    }

    public ReleaseResult releaseRequest(String deviceId, List<String> checks, String operator) {
        requireDevice(deviceId);
        String requestId = "REL-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return new ReleaseResult(requestId, "待审批", deviceId,
                operator == null ? "安全员" : operator, checks == null ? List.of() : checks,
                OffsetDateTime.now(clock).toString());
    }

    // ---------- 内部 ----------

    private void step(PairState pair, String id, String state, String detail) {
        pair.steps = pair.steps.stream().map(s -> s.id().equals(id) ? s.with(state, detail) : s).toList();
        // toList 返回不可变，转回可变以便后续继续改
        pair.steps = new ArrayList<>(pair.steps);
    }

    /** 距离 → 碰撞风险机器 code；雷达断数不产出风险等级（由 healthCode=UNCERTAIN 表达）。 */
    private String riskCodeOf(double distance) {
        if (distance < 3) {
            return CollisionRiskLevels.URGENT;
        }
        if (distance < 6) {
            return CollisionRiskLevels.SEVERE;
        }
        if (distance < 10) {
            return CollisionRiskLevels.WARNING;
        }
        return CollisionRiskLevels.SAFE;
    }

    /** 配对对外展示风险：感知不确定时为“待确认”，否则为碰撞风险中文等级。 */
    private static String displayRisk(PairState pair) {
        if (SensorHealth.UNCERTAIN.equals(pair.healthCode)
                || SensorHealth.RADAR_DOWN.equals(pair.healthCode)) {
            return SensorHealth.label(pair.healthCode);
        }
        return CollisionRiskLevels.label(pair.riskCode);
    }

    private static String successDetail(String id) {
        return switch (id) {
            case "detect" -> "识别到设备持续接近";
            case "alarm" -> "现场声光提醒已触发";
            case "driver" -> "司机终端已收到提醒";
            case "slow" -> "减速请求已发送";
            case "stop" -> "停机指令已发送";
            case "plc" -> "PLC 已确认设备停止";
            default -> "已完成";
        };
    }

    private static String dedupKey(PairState pair) {
        return "COLLISION:" + pair.currentId + ":" + pair.relatedId;
    }

    private static String firstArea(String a, String b) {
        return a != null ? a : b;
    }

    private PairState pairOfDevice(String id) {
        for (PairState p : pairs.values()) {
            if (p.currentId.equals(id) || p.relatedId.equals(id)) {
                return p;
            }
        }
        return null;
    }

    private PairState resolvePair(String id) {
        PairState p = pairOfDevice(id);
        if (p == null) {
            throw ApiException.notFound("设备不存在或无配对关系: " + id);
        }
        return p;
    }

    private DemoCollisionDevice requireDevice(String id) {
        return repository.findById(id).orElseThrow(() -> ApiException.notFound("设备不存在: " + id));
    }

    private void broadcastPair(PairState pair, String type) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("deviceId", pair.currentId);
        data.put("relatedDeviceId", pair.relatedId);
        data.put("distance", pair.distance);
        data.put("risk", displayRisk(pair));
        data.put("riskCode", pair.riskCode);
        data.put("healthCode", pair.healthCode);
        data.put("controlStatus", pair.plcStatus);
        data.put("alertId", pair.activeAlertId);
        publisher.publish(type, data);
    }

    // ---------- DTO ----------

    public record PairView(double distance, double relSpeed, String direction, int radarQuality,
                           String risk, boolean radarDown, String ts, String alertId,
                           List<CollisionStep> steps) {
        static PairView of(PairState p, String ts) {
            return new PairView(p.distance, p.relSpeed, p.direction, p.radarQuality, displayRisk(p),
                    p.radarDown, ts, p.activeAlertId, p.steps);
        }
    }

    public record SimulateResult(double distance, String risk, boolean radarDown, boolean deviceStopped,
                                 boolean controlFailure, String plcStatus, List<CollisionStep> steps,
                                 DemoCollisionDevice device, DemoCollisionDevice related, String alertId) {
        static SimulateResult of(PairState p, DemoCollisionDevice cur, DemoCollisionDevice rel) {
            return new SimulateResult(p.distance, displayRisk(p), p.radarDown, p.deviceStopped, p.controlFailure,
                    p.plcStatus, p.steps, cur, rel, p.activeAlertId);
        }
    }

    public record TakeoverResult(String plcStatus, String state, String time, String alertId) {
    }

    public record ReleaseResult(String requestId, String status, String deviceId, String operator,
                                List<String> checks, String time) {
    }
}
