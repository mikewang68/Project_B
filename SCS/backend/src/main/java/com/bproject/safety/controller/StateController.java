package com.bproject.safety.controller;

import com.bproject.safety.module.ai.model.AiReviewStatuses;
import com.bproject.safety.module.ai.model.DemoAiEvent;
import com.bproject.safety.module.ai.repository.AiEventRepository;
import com.bproject.safety.module.alert.model.AlertStatuses;
import com.bproject.safety.module.alert.model.DemoAlert;
import com.bproject.safety.module.alert.repository.AlertRepository;
import com.bproject.safety.module.collision.model.DemoCollisionDevice;
import com.bproject.safety.module.collision.repository.CollisionRepository;
import com.bproject.safety.module.fence.model.DemoFence;
import com.bproject.safety.module.fence.model.FenceStatuses;
import com.bproject.safety.module.fence.repository.FenceRepository;
import com.bproject.safety.module.personnel.model.DemoPersonnel;
import com.bproject.safety.module.personnel.repository.PersonnelRepository;
import com.bproject.safety.module.rule.model.DemoRule;
import com.bproject.safety.module.rule.model.RuleStatuses;
import com.bproject.safety.module.rule.repository.RuleRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 全局状态快照端点：提供 GET /api/v1/state，供前端 safetyStore.initialize() 消费，
 * 消除 404 导致的状态栏常驻「演示数据」兜底标记。
 */
@RestController
@RequestMapping("/api/v1")
@Tag(name = "State", description = "系统全局安全状态快照")
public class StateController {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");
    private final Clock clock;
    private final PersonnelRepository personnelRepository;
    private final FenceRepository fenceRepository;
    private final CollisionRepository collisionRepository;
    private final AiEventRepository aiEventRepository;
    private final AlertRepository alertRepository;
    private final RuleRepository ruleRepository;
    private final com.bproject.safety.support.demo.DemoFeatureGuard demoGuard;

    public StateController(
            Clock clock,
            PersonnelRepository personnelRepository,
            FenceRepository fenceRepository,
            CollisionRepository collisionRepository,
            AiEventRepository aiEventRepository,
            AlertRepository alertRepository,
            RuleRepository ruleRepository,
            com.bproject.safety.support.demo.DemoFeatureGuard demoGuard) {
        this.clock = clock;
        this.personnelRepository = personnelRepository;
        this.fenceRepository = fenceRepository;
        this.collisionRepository = collisionRepository;
        this.aiEventRepository = aiEventRepository;
        this.alertRepository = alertRepository;
        this.ruleRepository = ruleRepository;
        this.demoGuard = demoGuard;
    }

    @GetMapping("/state")
    @Operation(summary = "获取全局安全态势快照 (SafetySnapshot)")
    public Map<String, Object> getState() {
        String now = OffsetDateTime.now(clock.withZone(ZONE)).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("schemaVersion", "1.0.0");
        snapshot.put("updatedAt", now);
        snapshot.put("dataQuality", "valid");

        // 1. workPlan
        snapshot.put("workPlan", Map.of(
                "id", "WO-20260826-018",
                "name", "B区集装箱卸车作业",
                "shift", "白班 08:00-16:00",
                "team", "装卸一班",
                "area", "B区 1-4 号泊位",
                "progress", 68,
                "status", "作业中"
        ));

        // 2. people
        List<Map<String, Object>> peopleList = new ArrayList<>();
        List<DemoPersonnel> persons = personnelRepository.findAll();
        if (persons.isEmpty()) {
            if(demoGuard.isSeedEnabled())peopleList.addAll(defaultPeople(now));
        } else {
            for (DemoPersonnel p : persons) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", p.id);
                item.put("name", p.name != null ? p.name : p.id);
                item.put("team", p.team != null ? p.team : "装卸一班");
                item.put("role", "作业人员");
                item.put("bracelet", p.braceletId != null ? p.braceletId : "WB-" + p.id);
                item.put("x", p.x);
                item.put("y", p.y);
                item.put("battery", p.battery > 0 ? p.battery : 85);
                item.put("signal", -60);
                item.put("quality", p.positioningQuality != null ? p.positioningQuality : "优");
                item.put("status", p.state != null ? p.state : "normal");
                item.put("lastSeen", now);
                peopleList.add(item);
            }
        }
        snapshot.put("people", peopleList);

        // 3. fences
        List<Map<String, Object>> fencesList = new ArrayList<>();
        List<DemoFence> fences = fenceRepository.findAll();
        if (fences.isEmpty()) {
            if(demoGuard.isSeedEnabled())fencesList.addAll(defaultFences());
        } else {
            for (DemoFence f : fences) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", f.id);
                item.put("name", f.name);
                item.put("type", f.kind != null ? f.kind : "禁入区");
                double fx = f.polygon != null && !f.polygon.isEmpty() ? f.polygon.get(0).x() : 50.0;
                double fy = f.polygon != null && !f.polygon.isEmpty() ? f.polygon.get(0).y() : 50.0;
                item.put("x", fx);
                item.put("y", fy);
                item.put("width", 25.0);
                item.put("height", 20.0);
                item.put("level", f.getRiskLevel() != null ? f.getRiskLevel() : "严重");
                item.put("enabled", FenceStatuses.EFFECTIVE.equals(f.statusCode));
                item.put("version", f.version != null ? f.version : "V1.0");
                item.put("appliesTo", "全部人员");
                item.put("edgeSynced", true);
                fencesList.add(item);
            }
        }
        snapshot.put("fences", fencesList);

        // 4. devices
        List<Map<String, Object>> deviceList = new ArrayList<>();
        List<DemoCollisionDevice> devices = collisionRepository.findAll();
        if (devices.isEmpty()) {
            if(demoGuard.isSeedEnabled())deviceList.addAll(defaultDevices());
        } else {
            for (DemoCollisionDevice d : devices) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", d.id);
                item.put("name", d.name);
                item.put("type", d.type != null ? d.type : "车辆");
                item.put("x", d.x);
                item.put("y", d.y);
                item.put("speed", d.speed);
                item.put("direction", d.direction != null ? d.direction : "东向");
                item.put("distance", 15.0);
                item.put("threshold", 10.0);
                item.put("status", d.status != null ? d.status : "normal");
                item.put("sensorQuality", 95);
                item.put("controlState", d.controlStatus != null ? d.controlStatus : "运行");
                item.put("linkedPerson", "安全员");
                deviceList.add(item);
            }
        }
        snapshot.put("devices", deviceList);

        // 5. aiEvents
        List<Map<String, Object>> aiList = new ArrayList<>();
        List<DemoAiEvent> aiEvents = aiEventRepository.findRecent(50);
        long aiTotal = aiEventRepository.count();
        snapshot.put("aiEventWindow", Map.of("limit",50,"returned",aiEvents.size(),
                "total",aiTotal,"truncated",aiTotal>aiEvents.size()));
        if (aiEvents.isEmpty()) {
            if(demoGuard.isSeedEnabled())aiList.addAll(defaultAiEvents(now));
        } else {
            for (DemoAiEvent e : aiEvents) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", e.id);
                item.put("type", e.type != null ? e.type : "未佩戴安全帽");
                item.put("camera", e.camera != null ? e.camera : "CAM-01");
                item.put("area", e.area != null ? e.area : "作业区");
                double c = e.confidence >= 1.0 ? e.confidence / 100.0 : e.confidence;
                item.put("confidence", c > 0 ? c : 0.95);
                String st = "pending";
                if (AiReviewStatuses.CONFIRMED.equals(e.statusCode)) st = "confirmed";
                else if (AiReviewStatuses.FALSE_POSITIVE.equals(e.statusCode)) st = "false_positive";
                else if (AiReviewStatuses.UNCERTAIN.equals(e.statusCode)) st = "uncertain";
                item.put("status", st);
                item.put("occurredAt", now);
                item.put("modelVersion", e.model != null ? e.model : "PPE-2.4.1");
                aiList.add(item);
            }
        }
        snapshot.put("aiEvents", aiList);

        // 6. alarms
        List<Map<String, Object>> alarmList = new ArrayList<>();
        List<DemoAlert> alerts = alertRepository.findAll();
        if (alerts.isEmpty()) {
            if(demoGuard.isSeedEnabled())alarmList.addAll(defaultAlarms(now));
        } else {
            for (DemoAlert a : alerts) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", a.id);
                item.put("title", a.title);
                item.put("objectName", a.target != null ? a.target : a.area);
                item.put("area", a.area != null ? a.area : "现场");
                item.put("level", a.getRisk() != null ? a.getRisk() : "一般");
                item.put("source", a.source != null ? a.source : "人员定位");
                item.put("status", a.getStatus() != null ? a.getStatus() : "待处理");
                item.put("owner", a.assignee != null ? a.assignee : "安全员");
                item.put("occurredAt", a.time != null ? a.time : now);
                item.put("durationSeconds", a.durationSec);
                item.put("traceId", "tr-" + a.id);
                alarmList.add(item);
            }
        }
        snapshot.put("alarms", alarmList);

        // 7. rules
        List<Map<String, Object>> ruleList = new ArrayList<>();
        List<DemoRule> rules = ruleRepository.findAll();
        if (rules.isEmpty()) {
            if(demoGuard.isSeedEnabled())ruleList.addAll(defaultRules());
        } else {
            for (DemoRule r : rules) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("id", r.id);
                item.put("name", r.name);
                item.put("domain", r.category != null ? r.category : "安全管控");
                item.put("level", r.getRisk() != null ? r.getRisk() : "一般");
                item.put("version", r.version != null ? r.version : "V1.0");
                item.put("enabled", RuleStatuses.ACTIVE.equals(r.statusCode));
                item.put("edgeSynced", true);
                ruleList.add(item);
            }
        }
        snapshot.put("rules", ruleList);

        // 8. scenarios
        snapshot.put("scenarios", defaultScenarios());

        // 9. auditLogs
        snapshot.put("auditLogs", defaultAuditLogs(now));

        // 10. system
        snapshot.put("system", Map.of(
                "cloudConnected", true,
                "edgeVersion", "v2.4.0",
                "cachedEvents", 0,
                "availability", 99.98,
                "services", List.of(
                        Map.of("name", "人员定位引擎", "state", "healthy", "latencyMs", 12, "detail", "UWB基站连接正常"),
                        Map.of("name", "设备防碰撞", "state", "healthy", "latencyMs", 18, "detail", "毫米波雷达链路正常"),
                        Map.of("name", "AI视频分析", "state", "healthy", "latencyMs", 35, "detail", "8路视频流处理正常"),
                        Map.of("name", "边缘规则引擎", "state", "healthy", "latencyMs", 8, "detail", "规则版本对账一致")
                )
        ));

        if(!demoGuard.isSeedEnabled()){
            snapshot.put("dataQuality","partial");
            snapshot.put("workPlan",Map.of("id","","name","未接入生产计划","shift","","team","","area","","progress",0,"status","未接入"));
            snapshot.put("scenarios",List.of());snapshot.put("auditLogs",List.of());
            snapshot.put("system",Map.of("cloudConnected",false,"edgeVersion","未核验","cachedEvents",0,"availability",0,
                "services",List.of(Map.of("name","设备与边缘状态","state","unknown","latencyMs",0,"detail","等待在线状态接入，未核验健康指标"))));
        }
        return snapshot;
    }

    private static Map<String, Object> person(String id, String name, String team, String role, String bracelet,
                                              int x, int y, int battery, int signal, String quality, String status, String lastSeen) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("team", team);
        m.put("role", role);
        m.put("bracelet", bracelet);
        m.put("x", x);
        m.put("y", y);
        m.put("battery", battery);
        m.put("signal", signal);
        m.put("quality", quality);
        m.put("status", status);
        m.put("lastSeen", lastSeen);
        return m;
    }

    private List<Map<String, Object>> defaultPeople(String now) {
        return List.of(
                person("P-001", "张伟", "装卸一班", "司索工", "WB-001", 18, 25, 94, -56, "优", "normal", now),
                person("P-002", "李娜", "安全管理", "安全员", "WB-002", 34, 54, 82, -61, "优", "normal", now),
                person("P-003", "王强", "设备保障", "维修工", "WB-003", 71, 24, 67, -65, "良", "warning", now),
                person("P-004", "赵敏", "装卸二班", "装卸工", "WB-004", 79, 73, 76, -58, "优", "danger", now),
                person("P-005", "陈晨", "外协单位", "外协人员", "WB-005", 53, 68, 28, -74, "一般", "warning", now),
                person("P-006", "刘洋", "调度中心", "调度员", "WB-006", 45, 35, 91, -55, "优", "normal", now),
                person("P-007", "孙磊", "装卸一班", "装卸工", "WB-007", 22, 78, 73, -63, "良", "normal", now),
                person("P-008", "周静", "质量管理", "质检员", "WB-008", 89, 52, 58, -69, "良", "normal", now)
        );
    }

    private static Map<String, Object> fence(String id, String name, String type, double x, double y,
                                             double width, double height, String level, boolean enabled,
                                             String version, String appliesTo, boolean edgeSynced) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type);
        m.put("x", x);
        m.put("y", y);
        m.put("width", width);
        m.put("height", height);
        m.put("level", level);
        m.put("enabled", enabled);
        m.put("version", version);
        m.put("appliesTo", appliesTo);
        m.put("edgeSynced", edgeSynced);
        return m;
    }

    private List<Map<String, Object>> defaultFences() {
        return List.of(
                fence("FENCE-001", "龙门吊运行禁区", "禁入区", 64, 12, 27, 22, "严重", true, "V3.3", "地面作业人员", true),
                fence("FENCE-002", "翻箱机作业半径", "动态围栏", 62, 66, 28, 22, "紧急", true, "V2.5", "全部人员", true),
                fence("FENCE-003", "临时检修预警区", "预警区", 48, 49, 18, 16, "一般", true, "V1.8", "外协人员", true)
        );
    }

    private static Map<String, Object> device(String id, String name, String type, double x, double y,
                                              double speed, String direction, double distance, double threshold,
                                              String status, int sensorQuality, String controlState, String linkedPerson) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("name", name);
        m.put("type", type);
        m.put("x", x);
        m.put("y", y);
        m.put("speed", speed);
        m.put("direction", direction);
        m.put("distance", distance);
        m.put("threshold", threshold);
        m.put("status", status);
        m.put("sensorQuality", sensorQuality);
        m.put("controlState", controlState);
        m.put("linkedPerson", linkedPerson);
        return m;
    }

    private List<Map<String, Object>> defaultDevices() {
        return List.of(
                device("DEV-CRANE-01", "1#龙门吊", "龙门吊", 78, 23, 1.8, "东向", 24.6, 12, "normal", 98, "运行", "张伟"),
                device("DEV-TIP-02", "2#翻箱机", "翻箱机", 73, 72, 0.0, "固定", 5.7, 10, "danger", 96, "减速待确认", "赵敏"),
                device("DEV-TRUCK-07", "7#转运车", "转运车辆", 43, 48, 8.4, "西北", 16.2, 15, "warning", 93, "运行", "陈晨"),
                device("DEV-FORK-03", "3#叉车", "叉车", 24, 75, 5.1, "南向", 31.8, 8, "normal", 97, "运行", "孙磊")
        );
    }

    private List<Map<String, Object>> defaultAiEvents(String now) {
        return List.of(
                Map.of("id", "AI-20260826-031", "type", "未佩戴安全帽", "camera", "CAM-07", "area", "装卸作业区 A", "confidence", 0.96, "status", "pending", "occurredAt", now, "modelVersion", "PPE-2.4.1"),
                Map.of("id", "AI-20260826-030", "type", "跨越警戒线", "camera", "CAM-12", "area", "翻箱机南侧", "confidence", 0.88, "status", "pending", "occurredAt", now, "modelVersion", "BOUNDARY-1.8.2"),
                Map.of("id", "AI-20260826-029", "type", "人员闯入禁区", "camera", "CAM-03", "area", "龙门吊运行区", "confidence", 0.94, "status", "confirmed", "occurredAt", now, "modelVersion", "INTRUSION-3.1.0")
        );
    }

    private static Map<String, Object> alarm(String id, String title, String objectName, String area,
                                             String level, String source, String status, String owner,
                                             String occurredAt, int durationSeconds, String traceId) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("title", title);
        m.put("objectName", objectName);
        m.put("area", area);
        m.put("level", level);
        m.put("source", source);
        m.put("status", status);
        m.put("owner", owner);
        m.put("occurredAt", occurredAt);
        m.put("durationSeconds", durationSeconds);
        m.put("traceId", traceId);
        return m;
    }

    private List<Map<String, Object>> defaultAlarms(String now) {
        return List.of(
                alarm("ALM-20260826-1008", "翻箱机碰撞距离低于停机阈值", "2#翻箱机", "翻箱机南侧", "紧急", "设备防碰撞", "待接单", "B区安全员", now, 82, "tr-8f2f01"),
                alarm("ALM-20260826-1007", "人员进入龙门吊运行禁区", "赵敏", "龙门吊运行区", "严重", "电子围栏", "处理中", "李娜", now, 226, "tr-8f2d92"),
                alarm("ALM-20260826-1006", "外协手环电量低于 30%", "陈晨", "装卸作业线 B", "一般", "人员定位", "已派单", "王强", now, 416, "tr-8f2c43")
        );
    }

    private List<Map<String, Object>> defaultRules() {
        return List.of(
                Map.of("id", "RULE-PER-001", "name", "禁入区人员越界", "domain", "人员安全", "level", "严重", "version", "V3.2", "enabled", true, "edgeSynced", true),
                Map.of("id", "RULE-DEV-004", "name", "设备红色碰撞风险", "domain", "设备安全", "level", "紧急", "version", "V2.6", "enabled", true, "edgeSynced", true),
                Map.of("id", "RULE-AI-003", "name", "未佩戴安全帽识别", "domain", "AI违规", "level", "一般", "version", "V1.9", "enabled", true, "edgeSynced", true),
                Map.of("id", "RULE-NET-002", "name", "边缘网关断网自治", "domain", "集成运维", "level", "提示", "version", "V2.1", "enabled", true, "edgeSynced", true)
        );
    }

    private List<Map<String, Object>> defaultScenarios() {
        return List.of(
                Map.of("id", "person_intrusion", "name", "外协人员闯入禁区", "domain", "人员安全", "level", "严重", "duration", "约 10 秒", "description", "触发围栏、声光、大屏与派单，展示人员安全闭环。"),
                Map.of("id", "group_intrusion", "name", "多人同时进入危险区", "domain", "人员安全", "level", "紧急", "duration", "约 15 秒", "description", "展示多人事件聚合、并行通知与升级处置。"),
                Map.of("id", "device_collision", "name", "设备红色碰撞风险", "domain", "设备安全", "level", "紧急", "duration", "约 12 秒", "description", "距离降至 3.2m，触发停机接口与回执。"),
                Map.of("id", "ai_ppe", "name", "AI 未佩戴安全帽", "domain", "AI违规", "level", "一般", "duration", "约 8 秒", "description", "生成高置信度事件并进入人工复核。"),
                Map.of("id", "linkage_failure", "name", "紧急联动失败", "domain", "告警联动", "level", "紧急", "duration", "约 15 秒", "description", "模拟接口回执超时、自动升级和人工接管。"),
                Map.of("id", "network_offline", "name", "云边断网自治", "domain", "运维", "level", "提示", "duration", "约 10 秒", "description", "边缘规则继续运行，恢复后补传缓存事件。")
        );
    }

    private List<Map<String, Object>> defaultAuditLogs(String now) {
        return List.of(
                Map.of("id", "AUD-001", "time", now, "action", "规则版本对账", "operator", "边缘网关", "result", "一致", "traceId", "tr-8f1a01"),
                Map.of("id", "AUD-002", "time", now, "action", "作业计划同步", "operator", "调度接口", "result", "成功", "traceId", "tr-8f1a02")
        );
    }
}
