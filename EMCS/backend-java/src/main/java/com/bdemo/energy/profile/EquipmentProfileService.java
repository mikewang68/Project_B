package com.bdemo.energy.profile;

import com.bdemo.common.BusinessException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class EquipmentProfileService {
    private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;
    private final LocalDateTime demoNow;

    public EquipmentProfileService(JdbcTemplate jdbc, @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc;
        this.demoNow = demoNow;
    }

    public Map<String, Object> list(String zone, String energyType, String periodStart, String periodEnd) {
        Window window = window(periodStart, periodEnd);
        String energy = energy(energyType);
        String areaFilter = "ALL".equalsIgnoreCase(zone) ? "" : " AND a.area_code=?";
        List<Object> params = new ArrayList<>(List.of(energy, Timestamp.valueOf(window.start), Timestamp.valueOf(window.end),
                Timestamp.valueOf(window.end), Timestamp.valueOf(window.start)));
        if (!areaFilter.isEmpty()) params.add("AREA-" + zone.toUpperCase());
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT eq.equipment_id,eq.equipment_code,eq.equipment_name,eq.equipment_type,
                  eq.rated_power_kw,eq.energy_types,a.area_code,a.area_name,
                  COALESCE(h.period_energy,0) period_energy,COALESCE(h.coverage,0) coverage,
                  COALESCE(ae.abnormal_count,0) abnormal_count
                FROM e_equipment eq JOIN e_area a ON a.area_id=eq.area_id
                LEFT JOIN (SELECT object_id,SUM(total_value) period_energy,AVG(coverage_ratio) coverage
                  FROM e_stat_hour WHERE object_type='equipment' AND energy_type_code=?
                    AND stat_time>=? AND stat_time<? GROUP BY object_id) h ON h.object_id=eq.equipment_id
                LEFT JOIN (SELECT object_id,COUNT(*) abnormal_count FROM e_alert_event
                  WHERE object_type='equipment' AND first_occur_time<? AND last_occur_time>=? GROUP BY object_id) ae
                  ON ae.object_id=eq.equipment_id WHERE 1=1 %s ORDER BY a.area_code,eq.equipment_code
                """.formatted(areaFilter), params.toArray());
        double max = rows.stream().mapToDouble(row -> number(row.get("period_energy"))).max().orElse(0);
        double meanCoverage = rows.stream().mapToDouble(row -> number(row.get("coverage"))).average().orElse(0);
        List<Map<String, Object>> equipments = rows.stream().map(row -> map(
                "equipmentId", row.get("equipment_id"), "equipmentCode", row.get("equipment_code"),
                "equipmentName", row.get("equipment_name"), "equipmentType", row.get("equipment_type"),
                "area", map("code", row.get("area_code"), "name", row.get("area_name")),
                "energyTypes", split(row.get("energy_types")), "periodEnergy", round(number(row.get("period_energy")), 4),
                "ratedPowerKw", row.get("rated_power_kw"), "heatRatio", round(max == 0 ? 0 : number(row.get("period_energy")) / max, 4),
                "abnormalCount", row.get("abnormal_count"), "quality", quality(number(row.get("coverage"))))).toList();
        return map("signature", signature(window, zone, energyType, meanCoverage),
                "filters", map("zone", zone, "energyType", energyType, "periodStart", format(window.start), "periodEnd", format(window.end)),
                "equipments", equipments);
    }

    public Map<String, Object> detail(String equipmentCode, String energyType, String periodStart,
                                      String periodEnd, Long eventId) {
        Window window = window(periodStart, periodEnd);
        String energy = energy(energyType);
        List<Map<String, Object>> equipmentRows = jdbc.queryForList("""
                SELECT eq.*,a.area_code,a.area_name FROM e_equipment eq JOIN e_area a ON a.area_id=eq.area_id
                WHERE eq.equipment_code=?
                """, equipmentCode);
        if (equipmentRows.isEmpty()) throw new BusinessException(404, "设备不存在");
        Map<String, Object> equipment = equipmentRows.get(0);
        long equipmentId = ((Number) equipment.get("equipment_id")).longValue();
        Map<String, Object> focusEvent = null;
        if (eventId != null) {
            List<Map<String, Object>> events = jdbc.queryForList("""
                    SELECT * FROM e_alert_event WHERE event_id=? AND
                    ((object_type='equipment' AND object_id=?) OR (object_type='point' AND object_id IN
                    (SELECT point_id FROM e_meter_point WHERE equipment_id=?)))
                    """, eventId, equipmentId, equipmentId);
            if (events.isEmpty()) throw new BusinessException(404, "告警不属于该设备");
            focusEvent = events.get(0);
            window = new Window(min(window.start, dateTime(focusEvent.get("first_occur_time"))),
                    max(window.end, dateTime(focusEvent.get("last_occur_time")).plusHours(1)));
        }
        List<Map<String, Object>> hourly = jdbc.queryForList("""
                SELECT h.stat_time,h.total_value,h.avg_power_kw,h.coverage_ratio,h.quality_summary,
                  (SELECT s.status_value FROM e_equipment_status_log s WHERE s.equipment_id=h.object_id
                   AND s.event_time<=h.stat_time ORDER BY s.event_time DESC,s.log_id DESC LIMIT 1) state
                FROM e_stat_hour h WHERE h.object_type='equipment' AND h.object_id=? AND h.energy_type_code=?
                  AND h.stat_time>=? AND h.stat_time<? ORDER BY h.stat_time
                """, equipmentId, energy, Timestamp.valueOf(window.start), Timestamp.valueOf(window.end));
        List<Map<String, Object>> orders = orders(equipmentId, window);
        Map<String, BigDecimal> composition = composition(hourly, orders);
        Map<String, Object> peak = hourly.stream().max((a, b) -> Double.compare(power(a), power(b)))
                .map(row -> map("loadKw", round(power(row), 4), "occurredAt", format(row.get("stat_time"))))
                .orElseGet(() -> map("loadKw", null, "occurredAt", null));
        double coverage = hourly.stream().mapToDouble(row -> number(row.get("coverage_ratio"))).average().orElse(0);
        Map<String, Object> selectedEvent = focusEvent != null ? focusEvent : findR05Event(equipmentId, window);
        List<Map<String, Object>> focusOrders = selectedEvent == null ? orders : overlappingOrders(orders,
                dateTime(selectedEvent.get("first_occur_time")), dateTime(selectedEvent.get("last_occur_time")).plusHours(1));
        List<Map<String, Object>> alerts = alerts(equipmentId, window);
        return map(
                "equipment", map("equipmentId", equipmentId, "equipmentCode", equipment.get("equipment_code"),
                        "equipmentName", equipment.get("equipment_name"), "equipmentType", equipment.get("equipment_type"),
                        "ratedPowerKw", equipment.get("rated_power_kw"), "energyTypes", split(equipment.get("energy_types")),
                        "area", map("code", equipment.get("area_code"), "name", equipment.get("area_name"))),
                "composition", composition, "peak", peak, "peerComparison", peers(equipment, energy, window),
                "stateEnergySeries", series(hourly, energy, selectedEvent, window), "meterPoints", meterPoints(equipmentId),
                "workOrderMatch", map("matched", !focusOrders.isEmpty(), "orders", focusOrders,
                        "uncoveredWindows", selectedEvent != null && focusOrders.isEmpty() ? List.of(map(
                                "start", format(selectedEvent.get("first_occur_time")),
                                "end", format(dateTime(selectedEvent.get("last_occur_time")).plusHours(1)))) : List.of()),
                "shiftComparison", shifts(hourly, orders), "inefficiencyEvidence", evidence(selectedEvent, hourly, focusOrders),
                "alerts", alerts, "suggestions", suggestions(alerts),
                "quality", map("coverage", round(coverage * 100, 2), "band", quality(coverage)),
                "signature", signature(window, String.valueOf(equipment.get("area_code")), energyType, coverage));
    }

    private List<Map<String, Object>> orders(long equipmentId, Window window) {
        return jdbc.queryForList("""
                SELECT work_order_id,order_no,cargo_type,workload_value,workload_unit,start_time,end_time,status
                FROM e_work_order WHERE equipment_id=? AND status<>'cancelled' AND start_time<?
                  AND COALESCE(end_time,DATE_ADD(start_time,INTERVAL 8 HOUR))>? ORDER BY start_time
                """, equipmentId, Timestamp.valueOf(window.end), Timestamp.valueOf(window.start)).stream().map(row -> map(
                "workOrderId", row.get("work_order_id"), "orderNo", row.get("order_no"), "cargoType", row.get("cargo_type"),
                "workload", row.get("workload_value"), "workloadUnit", row.get("workload_unit"),
                "startTime", format(row.get("start_time")), "endTime", format(row.get("end_time")), "status", row.get("status"))).toList();
    }

    private Map<String, BigDecimal> composition(List<Map<String, Object>> hourly, List<Map<String, Object>> orders) {
        Map<String, BigDecimal> result = new LinkedHashMap<>();
        for (String key : List.of("workEnergy", "standbyEnergy", "stoppedEnergy", "auxiliaryEnergy", "totalEnergy")) result.put(key, BigDecimal.ZERO);
        for (Map<String, Object> row : hourly) {
            BigDecimal value = decimal(row.get("total_value")); LocalDateTime at = dateTime(row.get("stat_time"));
            result.put("totalEnergy", result.get("totalEnergy").add(value));
            String bucket = hasOrder(at, orders) ? "workEnergy" : "standby".equals(row.get("state")) ? "standbyEnergy" :
                    List.of("stopped", "maintenance").contains(row.get("state")) ? "stoppedEnergy" : "auxiliaryEnergy";
            result.put(bucket, result.get(bucket).add(value));
        }
        result.replaceAll((key, value) -> value.setScale(4, RoundingMode.HALF_UP)); return result;
    }

    private List<Map<String, Object>> peers(Map<String, Object> equipment, String energy, Window window) {
        return jdbc.queryForList("""
                SELECT eq.equipment_code,eq.equipment_name,SUM(h.total_value) value FROM e_equipment eq
                LEFT JOIN e_stat_hour h ON h.object_type='equipment' AND h.object_id=eq.equipment_id
                  AND h.energy_type_code=? AND h.stat_time>=? AND h.stat_time<?
                WHERE eq.equipment_type=? GROUP BY eq.equipment_id,eq.equipment_code,eq.equipment_name ORDER BY value DESC
                """, energy, Timestamp.valueOf(window.start), Timestamp.valueOf(window.end), equipment.get("equipment_type")).stream()
                .map(row -> map("equipmentCode", row.get("equipment_code"), "equipmentName", row.get("equipment_name"),
                        "value", round(number(row.get("value")), 4))).toList();
    }

    private Map<String, Object> series(List<Map<String, Object>> hourly, String energy, Map<String, Object> event, Window window) {
        List<Map<String, Object>> points = hourly.stream().map(row -> map("ts", format(row.get("stat_time")),
                "powerKw", round(power(row), 4), "state", row.get("state") == null ? "stopped" : row.get("state"),
                "quality", quality(number(row.get("coverage_ratio"))))).toList();
        List<Map<String, Object>> segments = new ArrayList<>();
        if (!points.isEmpty()) {
            String state = String.valueOf(points.get(0).get("state")); String start = String.valueOf(points.get(0).get("ts"));
            for (int i = 1; i < points.size(); i++) if (!state.equals(points.get(i).get("state"))) {
                segments.add(map("start", start, "end", points.get(i).get("ts"), "state", state));
                state = String.valueOf(points.get(i).get("state")); start = String.valueOf(points.get(i).get("ts"));
            }
            segments.add(map("start", start, "end", format(window.end), "state", state));
        }
        Object alertWindow = event == null ? null : map("start", format(event.get("first_occur_time")),
                "end", format(dateTime(event.get("last_occur_time")).plusHours(1)));
        return map("unit", "electricity".equals(energy) ? "kW" : "m³", "points", points,
                "stateSegments", segments, "alertWindow", alertWindow);
    }

    private List<Map<String, Object>> meterPoints(long equipmentId) { return jdbc.queryForList("""
            SELECT point_code,point_name,energy_type_code,unit FROM e_meter_point WHERE equipment_id=? ORDER BY point_id
            """, equipmentId).stream().map(row -> map("pointCode", row.get("point_code"), "pointName", row.get("point_name"),
            "energyType", row.get("energy_type_code"), "unit", row.get("unit"))).toList(); }

    private List<Map<String, Object>> alerts(long equipmentId, Window window) { return jdbc.queryForList("""
            SELECT e.event_id,e.rule_code,r.rule_name,e.level,e.status,e.first_occur_time,e.last_occur_time
            FROM e_alert_event e JOIN e_alert_rule r ON r.rule_id=e.rule_id WHERE e.object_type='equipment'
              AND e.object_id=? AND e.first_occur_time<? AND e.last_occur_time>=? ORDER BY e.first_occur_time
            """, equipmentId, Timestamp.valueOf(window.end), Timestamp.valueOf(window.start)).stream().map(row -> map(
            "eventId", row.get("event_id"), "ruleCode", row.get("rule_code"), "ruleName", row.get("rule_name"),
            "level", row.get("level"), "status", row.get("status"), "firstOccurredAt", format(row.get("first_occur_time")),
            "lastOccurredAt", format(row.get("last_occur_time")))).toList(); }

    private List<Map<String, Object>> suggestions(List<Map<String, Object>> alerts) {
        if (alerts.isEmpty()) return List.of();
        String placeholders = String.join(",", java.util.Collections.nCopies(alerts.size(), "?"));
        Object[] ids = alerts.stream().map(row -> row.get("eventId")).toArray();
        return jdbc.queryForList("SELECT suggestion_id,source_alert_id,title,status FROM e_suggestion WHERE source_alert_id IN (" + placeholders + ") ORDER BY suggestion_id", ids)
                .stream().map(row -> map("suggestionId", row.get("suggestion_id"), "sourceAlertId", row.get("source_alert_id"),
                        "title", row.get("title"), "status", row.get("status"))).toList();
    }

    private Map<String, Object> findR05Event(long equipmentId, Window window) { List<Map<String, Object>> rows = jdbc.queryForList("""
            SELECT * FROM e_alert_event WHERE object_type='equipment' AND object_id=? AND rule_code='R05'
              AND first_occur_time<? AND last_occur_time>=? ORDER BY first_occur_time LIMIT 1
            """, equipmentId, Timestamp.valueOf(window.end), Timestamp.valueOf(window.start)); return rows.isEmpty() ? null : rows.get(0); }

    private Map<String, Object> evidence(Map<String, Object> event, List<Map<String, Object>> hourly, List<Map<String, Object>> orders) {
        if (event == null || !"R05".equals(event.get("rule_code"))) return map("detected", false, "ruleCode", null,
                "durationHours", 0, "actualEnergy", 0.0, "peerAverage", 0.0, "deviationRatio", 0.0,
                "workOrderMatched", false, "reason", null);
        LocalDateTime start = dateTime(event.get("first_occur_time")), end = dateTime(event.get("last_occur_time"));
        double actual = hourly.stream().filter(row -> { LocalDateTime at = dateTime(row.get("stat_time")); return !at.isBefore(start) && !at.isAfter(end); })
                .mapToDouble(row -> number(row.get("total_value"))).average().orElse(0);
        return map("detected", true, "ruleCode", "R05", "durationHours", Math.max(1, java.time.Duration.between(start, end).toHours() + 1),
                "actualEnergy", round(actual, 4), "peerAverage", 0.0, "deviationRatio", 0.0,
                "workOrderMatched", !orders.isEmpty(), "reason", orders.isEmpty() ? "高耗 + 无工单" : "高耗且作业量未同步增加");
    }

    private List<Map<String, Object>> shifts(List<Map<String, Object>> hourly, List<Map<String, Object>> orders) {
        return List.of(shift("白班（08:00–20:00）", true, hourly, orders), shift("夜班（20:00–08:00）", false, hourly, orders));
    }
    private Map<String, Object> shift(String name, boolean day, List<Map<String, Object>> hourly, List<Map<String, Object>> orders) {
        double energy = hourly.stream().filter(row -> { int h = dateTime(row.get("stat_time")).getHour(); return day == (h >= 8 && h < 20); }).mapToDouble(row -> number(row.get("total_value"))).sum();
        double workload = orders.stream().filter(row -> { int h = parse(String.valueOf(row.get("startTime"))).getHour(); return day == (h >= 8 && h < 20); }).mapToDouble(row -> number(row.get("workload"))).sum();
        String unit = orders.isEmpty() ? "吨" : String.valueOf(orders.get(0).get("workloadUnit"));
        return map("shift", name, "energy", round(energy, 4), "workload", round(workload, 2), "workloadUnit", unit,
                "unitEnergy", workload == 0 ? null : round(energy / workload, 4), "trial", true);
    }

    private boolean hasOrder(LocalDateTime hour, List<Map<String, Object>> orders) { LocalDateTime end = hour.plusHours(1); return orders.stream().anyMatch(order -> { LocalDateTime a = parse(String.valueOf(order.get("startTime"))); LocalDateTime b = order.get("endTime") == null ? a.plusHours(8) : parse(String.valueOf(order.get("endTime"))); return a.isBefore(end) && b.isAfter(hour); }); }
    private List<Map<String, Object>> overlappingOrders(List<Map<String, Object>> orders, LocalDateTime start, LocalDateTime end) { return orders.stream().filter(order -> { LocalDateTime a = parse(String.valueOf(order.get("startTime"))); LocalDateTime b = order.get("endTime") == null ? a.plusHours(8) : parse(String.valueOf(order.get("endTime"))); return a.isBefore(end) && b.isAfter(start); }).toList(); }
    private Window window(String start, String end) { LocalDateTime finish = blank(end) ? demoNow : parse(end); LocalDateTime begin = blank(start) ? finish.minusDays(7) : parse(start); if (!begin.isBefore(finish)) throw new IllegalArgumentException("periodStart 必须早于 periodEnd"); return new Window(begin, finish); }
    private String energy(String value) { return switch (value.toUpperCase()) { case "ELEC" -> "electricity"; case "WATER" -> "water"; case "AIR" -> "compressed_air"; default -> throw new IllegalArgumentException("energyType 无效"); }; }
    private Map<String, Object> signature(Window window, String scope, String energy, double coverage) { String hash = Integer.toHexString((scope + energy + window.start + window.end).hashCode()); return map("version", "v0.3.4-demo", "formulaVersion", "f-1.3", "priceVersion", "PROFILE-N/A", "baselineVersion", "PROFILE-PEER-7D", "sigId", hash, "seed", 42, "generatedAt", format(window.end), "coverage", round(coverage * 100, 2)); }
    private String quality(double coverage) { return coverage >= .95 ? "ok" : coverage >= .8 ? "degraded" : "insufficient"; }
    private double power(Map<String, Object> row) { Object value = row.get("avg_power_kw"); return number(value == null ? row.get("total_value") : value); }
    private List<String> split(Object value) { return value == null ? List.of() : Arrays.stream(value.toString().split(",")).filter(part -> !part.isBlank()).toList(); }
    private BigDecimal decimal(Object value) { return value == null ? BigDecimal.ZERO : new BigDecimal(value.toString()); }
    private double number(Object value) { return value instanceof Number n ? n.doubleValue() : 0; }
    private double round(double value, int scale) { return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue(); }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private LocalDateTime parse(String value) { return LocalDateTime.parse(value.replace('T', ' '), DISPLAY); }
    private LocalDateTime dateTime(Object value) { return value instanceof LocalDateTime local ? local : ((Timestamp) value).toLocalDateTime(); }
    private String format(Object value) { if (value == null) return null; return (value instanceof LocalDateTime local ? local : dateTime(value)).format(DISPLAY); }
    private LocalDateTime min(LocalDateTime a, LocalDateTime b) { return a.isBefore(b) ? a : b; }
    private LocalDateTime max(LocalDateTime a, LocalDateTime b) { return a.isAfter(b) ? a : b; }
    private Map<String, Object> map(Object... pairs) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]); return out; }
    private record Window(LocalDateTime start, LocalDateTime end) {}
}
