package com.bproject.safety.module.publicdata.service;

import com.bproject.safety.module.ai.dto.AiRequests.AiIngestRequest;
import com.bproject.safety.module.ai.service.AiEventService;
import com.bproject.safety.module.collision.service.CollisionService;
import com.bproject.safety.module.personnel.service.PersonnelPositionService;
import com.bproject.safety.module.publicdata.repository.PublicDeviceBindings;
import com.bproject.safety.module.zone.service.ZoneStateService;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 公共底座已提交事件 → SCS 业务域映射（S3 字典 24 字段，逐字段核对状态见交接说明 §八）。
 *
 * <p>防御式提取：S3 字段依次在点值、event、payload 三层查找，缺省透传 null；
 * quality_code 四值与 null 原样保留（不得把缺失观测当成正常状态）。未知设备/点码按现有
 * ingest 哲学 ignored，不自动建档。UWB 的米制坐标直通（P95 标定待实际设备到位后核）。</p>
 */
@Component
public class PublicDeviceEventMapper {

    private static final Logger log = LoggerFactory.getLogger(PublicDeviceEventMapper.class);

    private final PublicDeviceBindings bindings;
    private final PersonnelPositionService personnelPositionService;
    private final CollisionService collisionService;
    private final ZoneStateService zoneStateService;
    private final AiEventService aiEventService;

    public PublicDeviceEventMapper(PublicDeviceBindings bindings,
                                   PersonnelPositionService personnelPositionService,
                                   CollisionService collisionService,
                                   ZoneStateService zoneStateService,
                                   AiEventService aiEventService) {
        this.bindings = bindings;
        this.personnelPositionService = personnelPositionService;
        this.collisionService = collisionService;
        this.zoneStateService = zoneStateService;
        this.aiEventService = aiEventService;
    }

    public record MappingResult(int applied, List<String> ignored, String note) {
    }

    /** 映射一条已提交事件。envelope 为 b.device-committed.v1 信封（含 event 正体）。 */
    public MappingResult map(JsonNode envelope) {
        JsonNode event = envelope.path("event");
        JsonNode payload = event.path("payload");
        List<JsonNode> points = new ArrayList<>();
        for (JsonNode p : payload.path("points")) {
            if (p.isObject()) {
                points.add(p);
            }
        }
        String deviceId = firstText(event, payload, points, "source_id");
        if (deviceId == null || deviceId.isBlank()) {
            return new MappingResult(0, List.of(), "missing source_id");
        }
        Optional<PublicDeviceBindings.Binding> binding = bindings.find(deviceId);
        if (binding.isEmpty() || !binding.get().enabled()) {
            return new MappingResult(0, List.of(deviceId), "unknown-or-disabled device");
        }
        PublicDeviceBindings.Binding b = binding.get();
        return switch (b.domain()) {
            case "RADAR" -> mapRadar(b, event, payload, points);
            case "UWB" -> mapUwb(b, event, points);
            case "AI" -> mapAi(b, envelope.path("event_id").asText(null), event, payload, points);
            case "DEVICE" -> mapDevice(b, event, payload, points);
            case "ZONE" -> mapZone(b, event, payload, points);
            case "OBSERVE" -> new MappingResult(1, List.of(), "observed-only (alarm light receipt)");
            default -> new MappingResult(0, List.of(deviceId), "unsupported domain " + b.domain());
        };
    }

    /** 测距雷达 → 配对设备距离/相对速度/质量（CollisionService 只更新已登记配对）。 */
    private MappingResult mapRadar(PublicDeviceBindings.Binding b, JsonNode event, JsonNode payload, List<JsonNode> points) {
        Double distance = firstDouble(event, payload, points, "distance_m");
        Double relSpeed = firstDouble(event, payload, points, "relative_speed_mps");
        String quality = firstText(event, payload, points, "quality_code");
        var reading = new CollisionService.ExternalRadarReading(
                b.objectCode(), distance, relSpeed, qualityToScore(quality), qualityToHealth(quality));
        var result = collisionService.applyExternalRadar(List.of(reading));
        return new MappingResult(result.applied(), result.ignored(), "radar distance=" + distance);
    }

    /** UWB 定位 → 人员实时位置（米制坐标直通，标定待实际设备核）。 */
    private MappingResult mapUwb(PublicDeviceBindings.Binding b, JsonNode event, List<JsonNode> points) {
        Double x = firstDouble(event, null, points, "x_m");
        Double y = firstDouble(event, null, points, "y_m");
        Double accuracy = firstDouble(event, null, points, "accuracy_m");
        boolean applied = personnelPositionService.apply(b.objectCode(), x, y, null, accuracy);
        return applied
                ? new MappingResult(1, List.of(), "uwb position applied")
                : new MappingResult(0, List.of(b.objectCode()), "unknown personnel " + b.objectCode());
    }

    /** AI 观测 → AI 违规事件（置信度 0-1×100；告警等级→风险；证据资产引用保留）。 */
    private MappingResult mapAi(PublicDeviceBindings.Binding b, String eventId, JsonNode event, JsonNode payload, List<JsonNode> points) {
        Double confidence01 = firstDouble(event, payload, points, "confidence");
        Double confidence = confidence01 == null ? null : Math.min(100.0, confidence01 * 100.0);
        String alarmLevel = firstText(event, payload, points, "alarm_level");
        String risk = alarmLevel == null ? null : switch (alarmLevel) {
            case "SEVERE", "URGENT" -> "HIGH";
            case "WARNING" -> "MEDIUM";
            case "NORMAL" -> "LOW";
            default -> null;
        };
        String ruleId = firstText(event, payload, points, "rule_id");
        OffsetDateTime occurred = firstTime(event, payload, points, "ts_event");
        String snapshot = firstText(event, payload, points, "snapshot_url");
        String quality = firstText(event, payload, points, "quality_code");
        String judge = firstText(event, payload, points, "judge_text");
        var request = new AiIngestRequest(
                firstText(event, payload, points, "event_type") != null
                        ? firstText(event, payload, points, "event_type")
                        : "公共底座 AI 观测",
                b.objectCode(), "公共底座 " + b.objectCode(), null,
                confidence, null, null, "public-device-committed", risk,
                "public", ruleId,
                judge != null ? judge : "公共底座已提交观测（质量 " + (quality == null ? "未标注" : quality) + "）",
                snapshot, new ArrayList<>(), null, b.deviceId(), occurred);
        aiEventService.ingest(request, "PUBLIC-COMMITTED:" + eventId);
        return new MappingResult(1, List.of(), "ai event mapped");
    }

    /** 龙门吊/翻箱机 → 设备运行状态（质量→健康三态；速度字段 S3 未定义，透传空）。 */
    private MappingResult mapDevice(PublicDeviceBindings.Binding b, JsonNode event, JsonNode payload, List<JsonNode> points) {
        String quality = firstText(event, payload, points, "quality_code");
        var state = new CollisionService.ExternalDeviceState(
                b.objectCode(), qualityToHealth(quality), null, null, null, null, null, null, null);
        var result = collisionService.applyExternalState(List.of(state));
        return new MappingResult(result.applied(), result.ignored(), "device state quality=" + quality);
    }

    /** 限位开关/位置传感器 → 装卸区状态（布尔字段缺失时透传 null，保留上一次值）。 */
    private MappingResult mapZone(PublicDeviceBindings.Binding b, JsonNode event, JsonNode payload, List<JsonNode> points) {
        Boolean working = firstBool(event, payload, points, "personnel_working");
        Boolean present = firstBool(event, payload, points, "vehicle_present");
        Double distance = firstDouble(event, payload, points, "distance_m");
        Integer distanceMm = distance == null ? null : (int) Math.round(distance * 1000);
        boolean applied = zoneStateService.apply(b.objectCode(), working, present, distanceMm, "public-committed");
        return applied
                ? new MappingResult(1, List.of(), "zone state applied")
                : new MappingResult(0, List.of(b.objectCode()), "unknown zone " + b.objectCode());
    }

    /** 质量码 → 健康三态（null 透传；STALE 视为降级，不伪装成正常）。 */
    static String qualityToHealth(String quality) {
        if (quality == null) {
            return null;
        }
        return switch (quality) {
            case "GOOD" -> "NORMAL";
            case "UNCERTAIN", "STALE" -> "DEGRADED";
            case "BAD" -> "DOWN";
            default -> null;
        };
    }

    /** 质量码 → 雷达质量分（0-100，构造换算，与 10-29 雷达接入口径一致）。 */
    static Double qualityToScore(String quality) {
        if (quality == null) {
            return null;
        }
        return switch (quality) {
            case "GOOD" -> 100.0;
            case "UNCERTAIN" -> 60.0;
            case "STALE" -> 40.0;
            case "BAD" -> 0.0;
            default -> null;
        };
    }

    // ---------- 防御式字段提取：点值 → event → payload，首个非空命中 ----------

    private static String firstText(JsonNode event, JsonNode payload, List<JsonNode> points, String field) {
        for (JsonNode p : points) {
            if (p.hasNonNull(field) && p.get(field).isTextual()) {
                return p.get(field).asText();
            }
        }
        for (JsonNode scope : new JsonNode[]{event, payload}) {
            if (scope != null && scope.hasNonNull(field) && scope.get(field).isTextual()) {
                return scope.get(field).asText();
            }
        }
        return null;
    }

    private static Double firstDouble(JsonNode event, JsonNode payload, List<JsonNode> points, String field) {
        for (JsonNode p : points) {
            if (p.hasNonNull(field) && p.get(field).isNumber()) {
                return p.get(field).asDouble();
            }
        }
        for (JsonNode scope : new JsonNode[]{event, payload}) {
            if (scope != null && scope.hasNonNull(field) && scope.get(field).isNumber()) {
                return scope.get(field).asDouble();
            }
        }
        return null;
    }

    private static Boolean firstBool(JsonNode event, JsonNode payload, List<JsonNode> points, String field) {
        for (JsonNode p : points) {
            if (p.hasNonNull(field) && p.get(field).isBoolean()) {
                return p.get(field).asBoolean();
            }
        }
        for (JsonNode scope : new JsonNode[]{event, payload}) {
            if (scope != null && scope.hasNonNull(field) && scope.get(field).isBoolean()) {
                return scope.get(field).asBoolean();
            }
        }
        return null;
    }

    private static OffsetDateTime firstTime(JsonNode event, JsonNode payload, List<JsonNode> points, String field) {
        String raw = firstText(event, payload, points, field);
        if (raw == null) {
            return null;
        }
        try {
            return OffsetDateTime.parse(raw);
        } catch (DateTimeParseException e) {
            log.debug("公共事件时间解析失败 field={} value={}", field, raw);
            return null;
        }
    }
}
