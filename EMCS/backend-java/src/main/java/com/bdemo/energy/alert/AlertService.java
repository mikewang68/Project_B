package com.bdemo.energy.alert;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlertService {
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final Set<String> CLOSED = Set.of("closed", "false_closed");
    private static final Map<String, Set<String>> LEGAL = Map.of(
            "new", Set.of("ack"), "ack", Set.of("dispatched"),
            "dispatched", Set.of("processing"),
            "processing", Set.of("closed", "false_closed", "escalated"));
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LocalDateTime demoNow;

    public AlertService(JdbcTemplate jdbc, ObjectMapper json,
                        @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc;
        this.json = json;
        this.demoNow = demoNow;
    }

    public Map<String, Object> list(String level, String status, String ruleCode, String zone,
                                    int pageNum, int pageSize) {
        requirePaging(pageNum, pageSize);
        Query query = eventQuery(level, status, ruleCode, zone, null, null);
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM (" + query.sql + ") q", Integer.class, query.params.toArray());
        List<Map<String, Object>> rows = jdbc.queryForList(query.sql + " ORDER BY e.last_occur_time DESC LIMIT ? OFFSET ?",
                append(query.params, pageSize, (pageNum - 1) * pageSize).toArray());
        LocalDateTime epoch = LocalDateTime.of(1970, 1, 1, 0, 0);
        Map<String, Object> stats = statistics(epoch, demoNow.plusSeconds(1), zone, ruleCode, level, status);
        stats.remove("events");
        return map("items", rows.stream().map(this::event).toList(), "total", total == null ? 0 : total,
                "statistics", stats, "filters", map("level", level, "status", status, "ruleCode", ruleCode,
                        "zone", zone, "pageNum", pageNum, "pageSize", pageSize));
    }

    public Map<String, Object> statistics(LocalDateTime start, LocalDateTime end, String zone,
                                           String ruleCode, String level, String status) {
        if (!end.isAfter(start)) throw new IllegalArgumentException("periodEnd 必须晚于 periodStart");
        Query query = eventQuery(level, status, ruleCode, zone, start, end);
        List<Map<String, Object>> rows = jdbc.queryForList(query.sql + " ORDER BY e.event_id", query.params.toArray());
        long closed = rows.stream().filter(row -> CLOSED.contains(string(row.get("status")))).count();
        long falseClosed = rows.stream().filter(row -> "false_closed".equals(row.get("status"))).count();
        double averageHours = rows.stream().filter(row -> row.get("closed_at") != null)
                .mapToDouble(row -> java.time.Duration.between(dateTime(row.get("first_occur_time")), dateTime(row.get("closed_at"))).toMinutes() / 60.0)
                .average().orElse(0);
        int total = rows.size();
        return map("total", total, "closeRate", round(total == 0 ? 0 : closed * 100.0 / total),
                "falsePositiveRate", round(total == 0 ? 0 : falseClosed * 100.0 / total),
                "averageHandleHours", round(averageHours), "events", rows.stream().map(row -> {
                    Map<String, Object> item = event(row);
                    item.put("snapshot", jsonObject(row.get("snapshot_json")));
                    return item;
                }).toList());
    }

    public Map<String, Object> detail(long eventId) {
        List<Map<String, Object>> rows = jdbc.queryForList(eventQuery(null, null, null, "ALL", null, null).sql + " AND e.event_id=?", eventId);
        if (rows.isEmpty()) throw new BusinessException(404, "告警事件不存在");
        Map<String, Object> row = rows.get(0);
        Map<String, Object> event = event(row);
        event.put("closedAt", format(row.get("closed_at")));
        event.put("closeType", row.get("close_type"));
        event.put("closeReason", row.get("close_reason"));

        List<Map<String, Object>> versions = jdbc.queryForList("""
                SELECT snapshot_json,effective_from FROM e_alert_rule_version
                WHERE rule_id=? AND version_no=?
                """, row.get("rule_id"), row.get("rule_version_no"));
        if (versions.isEmpty()) throw new BusinessException(409, "告警冻结规则版本缺失");
        Map<String, Object> version = versions.get(0);
        Map<String, Object> frozen = jsonObject(version.get("snapshot_json"));
        Map<String, Object> ruleSnapshot = map(
                "ruleCode", fallback(frozen.get("code"), row.get("rule_code")),
                "ruleName", fallback(frozen.get("name"), row.get("rule_name")),
                "expression", fallback(frozen.get("expr"), row.get("expression")),
                "thresholds", fallback(frozen.get("thresholds"), Map.of()),
                "level", fallback(frozen.get("level"), row.get("level")),
                "version", row.get("rule_version_no"), "effectiveFrom", format(version.get("effective_from")));
        List<Map<String, Object>> transitions = jdbc.queryForList("""
                SELECT from_status,to_status,operator,remark,occur_time FROM e_alert_flow_log
                WHERE event_id=? ORDER BY occur_time,flow_id
                """, eventId).stream().map(this::flow).toList();
        return map("event", event, "ruleSnapshot", ruleSnapshot, "curveSnapshot", curve(row),
                "workOrderComparison", workOrders(row), "transitions", transitions,
                "notifications", jsonList(row.get("notification_json")),
                "profileEquipmentId", profileEquipment(row), "convertedToSuggestion", converted(eventId),
                "suggestionId", suggestionId(eventId), "costDeepLink", costLink(row));
    }

    @Transactional
    public Map<String, Object> transition(long eventId, AlertController.TransitionRequest body, String operator) {
        List<Map<String, Object>> rows = jdbc.queryForList("SELECT status FROM e_alert_event WHERE event_id=? FOR UPDATE", eventId);
        if (rows.isEmpty()) throw new BusinessException(404, "告警事件不存在");
        String current = string(rows.get(0).get("status"));
        if (body.remark() == null || body.remark().isBlank()) throw new IllegalArgumentException("流转备注 remark 不能为空");
        List<String[]> steps = plan(current, body);
        for (String[] step : steps) jdbc.update("""
                INSERT INTO e_alert_flow_log(event_id,from_status,to_status,operator,remark,occur_time)
                VALUES(?,?,?,?,?,?)
                """, eventId, step[0], step[1], operator, body.remark().trim(), Timestamp.valueOf(demoNow));
        String assigned = "dispatched".equals(body.toStatus()) ? body.assignedTo().trim() : null;
        String closeType = "false_closed".equals(body.toStatus()) ? "false_positive" : "closed".equals(body.toStatus()) ? "valid" : null;
        jdbc.update("""
                UPDATE e_alert_event SET status=?, assigned_to=COALESCE(?,assigned_to),
                  closed_at=?, close_reason=?, close_type=?, update_time=? WHERE event_id=?
                """, body.toStatus(), assigned, CLOSED.contains(body.toStatus()) ? Timestamp.valueOf(demoNow) : null,
                CLOSED.contains(body.toStatus()) ? body.closeReason().trim() : null, closeType,
                Timestamp.valueOf(demoNow), eventId);
        return map("event", detail(eventId).get("event"), "transitions", steps.stream().map(step -> map(
                "fromStatus", step[0], "toStatus", step[1], "operator", operator,
                "remark", body.remark().trim(), "occurredAt", format(demoNow))).toList());
    }

    private List<String[]> plan(String current, AlertController.TransitionRequest body) {
        String target = body.toStatus();
        if (target == null) throw new IllegalArgumentException("toStatus 不能为空");
        if ("dispatched".equals(target) && (body.assignedTo() == null || body.assignedTo().isBlank()))
            throw new BusinessException(409, "派发必须填写 assignedTo");
        if (CLOSED.contains(target) && (body.closeReason() == null || body.closeReason().isBlank()))
            throw new BusinessException(409, "关闭必须填写 closeReason");
        if ("new".equals(current) && "dispatched".equals(target))
            return List.of(new String[]{"new", "ack"}, new String[]{"ack", "dispatched"});
        if (!LEGAL.getOrDefault(current, Set.of()).contains(target))
            throw new BusinessException(409, "非法告警迁移：" + current + " -> " + target);
        return java.util.Collections.singletonList(new String[]{current, target});
    }

    private Query eventQuery(String level, String status, String ruleCode, String zone,
                             LocalDateTime start, LocalDateTime end) {
        StringBuilder sql = new StringBuilder("""
                SELECT e.*,r.rule_name,r.rule_category,r.expression,a.area_code,a.area_name,
                  eq.equipment_code,eq.equipment_name,eq.equipment_id event_equipment_id,
                  p.point_code,p.point_name,p.unit,p.equipment_id point_equipment_id
                FROM e_alert_event e JOIN e_alert_rule r ON r.rule_id=e.rule_id
                LEFT JOIN e_area a ON a.area_id=e.area_id
                LEFT JOIN e_equipment eq ON e.object_type='equipment' AND eq.equipment_id=e.object_id
                LEFT JOIN e_meter_point p ON e.object_type='point' AND p.point_id=e.object_id WHERE 1=1
                """);
        List<Object> params = new ArrayList<>();
        add(sql, params, " AND e.level=?", level); add(sql, params, " AND e.status=?", status);
        add(sql, params, " AND e.rule_code=?", ruleCode);
        if (zone != null && !"ALL".equalsIgnoreCase(zone)) add(sql, params, " AND a.area_code=?", "AREA-" + zone.toUpperCase());
        if (start != null) add(sql, params, " AND e.first_occur_time>=?", Timestamp.valueOf(start));
        if (end != null) add(sql, params, " AND e.first_occur_time<?", Timestamp.valueOf(end));
        return new Query(sql.toString(), params);
    }

    private Map<String, Object> event(Map<String, Object> row) {
        String type = string(row.get("object_type")); Object code; Object name;
        if ("equipment".equals(type)) { code = row.get("equipment_code"); name = row.get("equipment_name"); }
        else if ("point".equals(type)) { code = row.get("point_code"); name = row.get("point_name"); }
        else if ("area".equals(type)) { code = row.get("area_code"); name = row.get("area_name"); }
        else { code = "SYSTEM"; name = "全站"; }
        return map("eventId", row.get("event_id"), "level", row.get("level"), "status", row.get("status"),
                "ruleCode", row.get("rule_code"), "ruleName", row.get("rule_name"),
                "ruleCategory", row.get("rule_category"), "ruleVersion", row.get("rule_version_no"),
                "object", map("type", type, "id", row.get("object_id"), "code", code, "name", name),
                "area", map("code", row.get("area_code"), "name", fallback(row.get("area_name"), "全站")),
                "firstOccurredAt", format(row.get("first_occur_time")), "lastOccurredAt", format(row.get("last_occur_time")),
                "occurCount", fallback(row.get("occur_count"), 1), "assignedTo", row.get("assigned_to"));
    }

    private Map<String, Object> curve(Map<String, Object> row) {
        LocalDateTime start = dateTime(row.get("first_occur_time")); LocalDateTime end = dateTime(row.get("last_occur_time")).plusHours(1);
        List<Map<String, Object>> points; String unit;
        if ("point".equals(row.get("object_type"))) {
            points = jdbc.queryForList("SELECT sample_time ts,incremental_value value FROM e_raw_reading WHERE point_id=? AND sample_time>=? AND sample_time<? ORDER BY sample_time",
                    row.get("object_id"), Timestamp.valueOf(start), Timestamp.valueOf(end)); unit = string(row.get("unit"));
        } else {
            String energy = "R06".equals(row.get("rule_code")) ? "compressed_air" : "electricity";
            points = jdbc.queryForList("SELECT stat_time ts,total_value value FROM e_stat_hour WHERE object_type=? AND object_id=? AND energy_type_code=? AND stat_time>=? AND stat_time<? ORDER BY stat_time",
                    row.get("object_type"), row.get("object_id"), energy, Timestamp.valueOf(start), Timestamp.valueOf(end));
            unit = "compressed_air".equals(energy) ? "m³" : "kWh";
        }
        return map("unit", unit, "points", points.stream().map(point -> map("ts", format(point.get("ts")), "value", point.get("value"))).toList(),
                "window", map("start", format(start), "end", format(end)));
    }

    private Map<String, Object> workOrders(Map<String, Object> row) {
        Object equipment = fallback(row.get("event_equipment_id"), row.get("point_equipment_id"));
        if (equipment == null) return map("matched", false, "orders", List.of());
        LocalDateTime start = dateTime(row.get("first_occur_time")); LocalDateTime end = dateTime(row.get("last_occur_time")).plusHours(1);
        List<Map<String, Object>> orders = jdbc.queryForList("""
                SELECT work_order_id,order_no,cargo_type,workload_value,workload_unit,start_time,end_time,status
                FROM e_work_order WHERE equipment_id=? AND status<>'cancelled' AND start_time<?
                AND COALESCE(end_time,(CAST(start_time AS timestamp) + INTERVAL '8 HOUR'))>? ORDER BY start_time
                """, equipment, Timestamp.valueOf(end), Timestamp.valueOf(start)).stream().map(order -> map(
                "workOrderId", order.get("work_order_id"), "orderNo", order.get("order_no"), "cargoType", order.get("cargo_type"),
                "workload", order.get("workload_value"), "workloadUnit", order.get("workload_unit"),
                "startTime", format(order.get("start_time")), "endTime", format(order.get("end_time")), "status", order.get("status"))).toList();
        return map("matched", !orders.isEmpty(), "orders", orders);
    }

    private Object profileEquipment(Map<String, Object> row) {
        Object direct = row.get("equipment_code"); if (direct != null) return direct;
        Object id = row.get("point_equipment_id"); if (id == null) return null;
        List<String> codes = jdbc.query("SELECT equipment_code FROM e_equipment WHERE equipment_id=?", (rs, n) -> rs.getString(1), id);
        return codes.isEmpty() ? null : codes.get(0);
    }

    private boolean converted(long eventId) { return suggestionId(eventId) != null; }
    private Long suggestionId(long eventId) {
        List<Long> ids = jdbc.query("SELECT suggestion_id FROM e_suggestion WHERE source_alert_id=? ORDER BY suggestion_id LIMIT 1",
                (rs, n) -> rs.getLong(1), eventId); return ids.isEmpty() ? null : ids.get(0);
    }
    private Object costLink(Map<String, Object> row) {
        if (!"R10".equals(row.get("rule_code"))) return null;
        Map<String, Object> snapshot = jsonObject(row.get("snapshot_json"));
        String code = string(row.get("area_code")); String zone = code == null ? "ALL" : code.replace("AREA-", "");
        return map("path", "/energy/cost/record", "params", map("statMonth", snapshot.get("report_month"),
                "zone", zone, "energyType", "electricity", "focus", "R10", "sourceEventId", row.get("event_id")));
    }

    private Map<String, Object> flow(Map<String, Object> row) { return map("fromStatus", row.get("from_status"),
            "toStatus", row.get("to_status"), "operator", row.get("operator"), "remark", row.get("remark"), "occurredAt", format(row.get("occur_time"))); }
    private void requirePaging(int page, int size) { if (page < 1 || size < 1 || size > 100) throw new IllegalArgumentException("分页参数无效"); }
    private void add(StringBuilder sql, List<Object> params, String clause, Object value) { if (value != null && !value.toString().isBlank()) { sql.append(clause); params.add(value); } }
    private List<Object> append(List<Object> values, Object... more) { List<Object> all = new ArrayList<>(values); all.addAll(List.of(more)); return all; }
    private String string(Object value) { return value == null ? null : value.toString(); }
    private Object fallback(Object value, Object fallback) { return value == null ? fallback : value; }
    private double round(double value) { return Math.round(value * 100.0) / 100.0; }
    private LocalDateTime dateTime(Object value) { if (value instanceof LocalDateTime local) return local; return ((Timestamp) value).toLocalDateTime(); }
    private String format(Object value) { return value == null ? null : dateTime(value).format(DATE_TIME); }
    private Map<String, Object> jsonObject(Object raw) { if (raw == null) return new LinkedHashMap<>(); try { return json.readValue(raw.toString(), new TypeReference<>() {}); } catch (Exception ignored) { return new LinkedHashMap<>(); } }
    private List<Map<String, Object>> jsonList(Object raw) { if (raw == null) return List.of(); try { return json.readValue(raw.toString(), new TypeReference<>() {}); } catch (Exception ignored) { return List.of(); } }
    private Map<String, Object> map(Object... pairs) { Map<String, Object> result = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]); return result; }
    private record Query(String sql, List<Object> params) {}
}
