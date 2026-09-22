package com.bdemo.energy.overview;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class OverviewService {
    private static final DateTimeFormatter HOUR = DateTimeFormatter.ofPattern("HH:mm");
    private static final List<String> OPEN_ALERTS = List.of("new", "ack", "dispatched", "processing", "escalated");
    private final JdbcTemplate jdbc;
    private final LocalDateTime demoNow;

    public OverviewService(JdbcTemplate jdbc, @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc;
        this.demoNow = demoNow;
    }

    // REQ-057/058/062: one read model for the complete overview page.
    public Map<String, Object> summary(String timeRange, String zone, String energyType) {
        String energy = energyCode(energyType);
        Long areaId = areaId(zone);
        LocalDate today = demoNow.toLocalDate();
        BigDecimal todayUsage = dayUsage(today, energy, areaId);
        BigDecimal yesterdayUsage = dayUsage(today.minusDays(1), energy, areaId);
        double coverage = coverage(today, areaId);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("signature", signature(today, energy, coverage));
        result.put("demoState", map(
                "enabled", true,
                "hint", "REQ-029 能源基线演示态：固定种子构造数据，结果不用于正式考核",
                "reqAnchor", "REQ-029"));
        result.put("filters", map("timeRange", timeRange, "zone", zone, "energyType", energyType));
        result.put("kpis", kpis(today, energy, areaId, todayUsage, yesterdayUsage, coverage));
        result.put("trend", trend(today, energy, areaId));
        result.put("topObjects", topCost(today, areaId));
        result.put("topObjectsByEnergy", topEnergy(today, energy, areaId));
        result.put("alarms", alarms(areaId));
        result.put("quality", quality(today, areaId, coverage));
        return result;
    }

    private Map<String, Object> signature(LocalDate today, String energy, double coverage) {
        String baseline = scalarString("""
                SELECT COALESCE(MAX(baseline_code), 'NO-BASELINE') FROM e_energy_baseline
                WHERE energy_type_code=? AND status='published'
                """, energy);
        String price = scalarString("""
                SELECT CONCAT('TARIFF-v', COALESCE(MAX(version_no), 0)) FROM e_tariff_version
                WHERE energy_type_code=? AND effective_from<=?
                  AND (effective_to IS NULL OR effective_to>=?)
                """, energy, Date.valueOf(today), Date.valueOf(today));
        String sigId = Integer.toHexString((today + energy + baseline + price + coverage).hashCode()).toUpperCase();
        return map(
                "version", "v0.3.4-demo",
                "formulaVersion", "f-1.3",
                "priceVersion", price,
                "baselineVersion", baseline,
                "sigId", sigId,
                "seed", 42,
                "generatedAt", demoNow.toString(),
                "coverage", coverage);
    }

    private List<Map<String, Object>> kpis(
            LocalDate today, String energy, Long areaId, BigDecimal usage, BigDecimal yesterday, double coverage) {
        double usageDelta = percentChange(usage, yesterday);
        Double baselineDeviation = baselineDeviation(today, energy, areaId);
        int openAlarms = count("SELECT COUNT(*) FROM e_alert_event WHERE status IN (?,?,?,?,?)" + areaClause(areaId),
                paramsWithArea(areaId, OPEN_ALERTS.toArray()));
        BigDecimal cost = amount("""
                SELECT COALESCE(SUM(total_cost),0) FROM e_cost_record
                WHERE stat_month=? AND energy_type_code=? AND is_current=1 AND object_type='area'
                """ + objectAreaClause(areaId), paramsWithArea(areaId, today.toString().substring(0, 7), energy));
        int suggestions = count("""
                SELECT COUNT(*) FROM e_suggestion WHERE status IN ('pending','dispatched','executing')
                """ + areaClause(areaId), paramsWithArea(areaId));
        String unit = "electricity".equals(energy) ? "kWh" : "m³";
        List<Map<String, Object>> cards = new ArrayList<>();
        cards.add(card("ENERGY_DAY", "当日总能耗", unit, rounded(usage, energy), "实时", "ok",
                signed(usageDelta) + "%", usageDelta >= 0 ? "up" : "dn", "环比昨日", "当日区总表口径", "REQ-053"));
        cards.add(card("BASELINE_DEV", "基线偏差", "%", baselineDeviation == null ? "不适用" : round(baselineDeviation, 2),
                baselineDeviation != null && baselineDeviation > 20 ? "超阈" : "正常",
                baselineDeviation != null && baselineDeviation > 20 ? "warn" : "ok",
                baselineDeviation == null ? "—" : signed(baselineDeviation) + "%", "neutral", "较基线", "发布基线", "REQ-029"));
        cards.add(card("OPEN_ALARMS", "未关闭告警", "条", openAlarms, openAlarms > 0 ? "关注" : "正常",
                openAlarms > 0 ? "hi" : "ok", Integer.toString(openAlarms), "neutral", "当前", "规则引擎真算", "REQ-039"));
        cards.add(card("COVERAGE", "数据覆盖率", "%", coverage, coverage >= 95 ? "合格" : coverage >= 80 ? "降级" : "不足",
                coverage >= 95 ? "ok" : "warn", round(coverage - 95, 2) + "%", coverage >= 95 ? "up" : "dn", "较95%阈值", "当日累计", "REQ-019"));
        cards.add(card("COST_MTD", "本月能源成本", "¥", cost.setScale(2, RoundingMode.HALF_UP), "月累计", "neutral",
                "当前版本", "neutral", "", "已发布单价", "REQ-053"));
        cards.add(card("SUGGESTIONS", "待办节能建议", "条", suggestions, suggestions > 0 ? "待办" : "清零", suggestions > 0 ? "warn" : "ok",
                Integer.toString(suggestions), "neutral", "待审核/已分派/执行中", "闭环状态", "REQ-045"));
        return cards;
    }

    private Map<String, Object> trend(LocalDate today, String energy, Long areaId) {
        String valueColumn = "electricity".equals(energy) ? "SUM(COALESCE(avg_power_kw,total_value))" : "SUM(total_value)";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT EXTRACT(HOUR FROM CAST(stat_time AS timestamp)) hour_no, %s value
                FROM e_stat_hour WHERE object_type='area' AND energy_type_code=? AND CAST(stat_time AS date)=?
                %s GROUP BY EXTRACT(HOUR FROM CAST(stat_time AS timestamp)) ORDER BY hour_no
                """.formatted(valueColumn, objectAreaClause(areaId)), paramsWithArea(areaId, energy, Date.valueOf(today)));
        List<Map<String, Object>> baselines = jdbc.queryForList("""
                SELECT hour_no, AVG(day_value) value FROM (
                  SELECT CAST(stat_time AS date) day_no, EXTRACT(HOUR FROM CAST(stat_time AS timestamp)) hour_no, %s day_value
                  FROM e_stat_hour WHERE object_type='area' AND energy_type_code=?
                    AND stat_time>=? AND stat_time<? %s
                  GROUP BY CAST(stat_time AS date), EXTRACT(HOUR FROM CAST(stat_time AS timestamp))
                ) history GROUP BY hour_no ORDER BY hour_no
                """.formatted(valueColumn, objectAreaClause(areaId)), paramsWithArea(areaId, energy,
                Timestamp.valueOf(today.minusWeeks(8).atStartOfDay()), Timestamp.valueOf(today.atStartOfDay())));
        double[] load = new double[24];
        double[] mid = new double[24];
        rows.forEach(row -> load[number(row.get("hour_no")).intValue()] = round(number(row.get("value")).doubleValue(), precision(energy)));
        baselines.forEach(row -> mid[number(row.get("hour_no")).intValue()] = round(number(row.get("value")).doubleValue(), precision(energy)));
        int peakIndex = 0;
        int valleyIndex = 0;
        for (int i = 1; i < load.length; i++) {
            if (load[i] > load[peakIndex]) peakIndex = i;
            if (load[i] < load[valleyIndex]) valleyIndex = i;
        }
        List<String> hours = new ArrayList<>();
        List<Double> high = new ArrayList<>();
        List<Double> low = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            hours.add(String.format("%02d:00", i));
            high.add(round(mid[i] * 1.12, precision(energy)));
            low.add(round(mid[i] * 0.88, precision(energy)));
        }
        return map(
                "hours", hours,
                "load", Arrays.stream(load).boxed().toList(),
                "baselineHigh", high,
                "baselineMid", Arrays.stream(mid).boxed().toList(),
                "baselineLow", low,
                "anomalies", List.of(),
                "peak", map("hour", hours.get(peakIndex), "value", load[peakIndex], "note", "当日峰值"),
                "valley", map("hour", hours.get(valleyIndex), "value", load[valleyIndex]),
                "nowIndex", Math.min(23, demoNow.getHour()),
                "unit", "electricity".equals(energy) ? "kW" : "m³/h",
                "samplingInterval", "compressed_air".equals(energy) ? "5min" : "15min",
                "formulaVersion", "f-1.3");
    }

    private List<Map<String, Object>> topEnergy(LocalDate today, String energy, Long areaId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.equipment_name name, a.area_name area_name, SUM(s.total_value) value
                FROM e_stat_day s JOIN e_equipment e ON e.equipment_id=s.object_id
                JOIN e_area a ON a.area_id=e.area_id
                WHERE s.object_type='equipment' AND s.energy_type_code=?
                  AND to_char(CAST(s.stat_date AS timestamp),'YYYY-MM')=? %s
                GROUP BY e.equipment_id,e.equipment_name,a.area_name ORDER BY value DESC LIMIT 5
                """.formatted(areaId == null ? "" : "AND e.area_id=?"), paramsWithArea(areaId, energy, today.toString().substring(0, 7)));
        return rank(rows, "electricity".equals(energy) ? "kWh" : "m³");
    }

    private List<Map<String, Object>> topCost(LocalDate today, Long areaId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.equipment_name name, a.area_name area_name, SUM(c.total_cost) value
                FROM e_cost_record c JOIN e_equipment e ON e.equipment_id=c.object_id
                JOIN e_area a ON a.area_id=e.area_id
                WHERE c.object_type='equipment' AND c.is_current=1 AND c.stat_month=? %s
                GROUP BY e.equipment_id,e.equipment_name,a.area_name ORDER BY value DESC LIMIT 5
                """.formatted(areaId == null ? "" : "AND e.area_id=?"), paramsWithArea(areaId, today.toString().substring(0, 7)));
        return rank(rows, "¥");
    }

    private List<Map<String, Object>> rank(List<Map<String, Object>> rows, String unit) {
        double total = rows.stream().mapToDouble(r -> number(r.get("value")).doubleValue()).sum();
        double max = rows.isEmpty() ? 0 : number(rows.get(0).get("value")).doubleValue();
        List<Map<String, Object>> ranked = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            Map<String, Object> row = rows.get(i);
            double value = number(row.get("value")).doubleValue();
            ranked.add(map("rank", i + 1, "name", row.get("name"), "meta", row.get("area_name"),
                    "value", round(value, 2), "unit", unit, "share", round(total == 0 ? 0 : value * 100 / total, 1) + "%",
                    "barPct", round(max == 0 ? 0 : value * 100 / max, 1), "kind", i == 0 ? "hot" : "normal"));
        }
        return ranked;
    }

    private List<Map<String, Object>> alarms(Long areaId) {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT e.last_occur_time, e.level, e.rule_code, r.rule_name, e.rule_version_no,
                       e.occur_count, COALESCE(eq.equipment_name,a.area_name,p.point_name,'未知对象') object_name
                FROM e_alert_event e JOIN e_alert_rule r ON r.rule_id=e.rule_id
                LEFT JOIN e_equipment eq ON e.object_type='equipment' AND eq.equipment_id=e.object_id
                LEFT JOIN e_area a ON e.object_type='area' AND a.area_id=e.object_id
                LEFT JOIN e_meter_point p ON e.object_type='point' AND p.point_id=e.object_id
                WHERE 1=1 %s ORDER BY e.last_occur_time DESC LIMIT 6
                """.formatted(areaId == null ? "" : " AND e.area_id=?"), paramsWithArea(areaId));
        return rows.stream().map(row -> map(
                "time", dateTime(row.get("last_occur_time")).format(HOUR),
                "level", level(row.get("level").toString()),
                "ruleId", row.get("rule_code"),
                "title", row.get("rule_name"),
                "ruleVersion", "v" + row.get("rule_version_no"),
                "mergedCount", row.get("occur_count"),
                "obj", row.get("object_name"))).toList();
    }

    private Map<String, Object> quality(LocalDate today, Long areaId, double coverage) {
        List<Map<String, Object>> latest = jdbc.queryForList("""
                SELECT p.point_code, r.quality_state FROM e_meter_point p
                LEFT JOIN e_raw_reading r ON r.point_id=p.point_id
                  AND r.sample_time=(SELECT MAX(r2.sample_time) FROM e_raw_reading r2 WHERE r2.point_id=p.point_id)
                WHERE 1=1 %s ORDER BY p.area_id,p.point_id
                """.formatted(areaClauseForPoint(areaId)), paramsWithArea(areaId));
        List<String> cells = latest.stream().map(row -> qualityKey((String) row.get("quality_state"))).toList();
        List<Map<String, Object>> categories = new ArrayList<>();
        for (String key : List.of("ok", "miss", "late", "est", "fix")) {
            long count = cells.stream().filter(key::equals).count();
            categories.add(map("key", key, "label", qualityLabel(key), "count", count,
                    "pct", (cells.isEmpty() ? 0 : round(count * 100.0 / cells.size(), 1)) + "%"));
        }
        String latestIngest = scalarString("SELECT to_char(CAST(MAX(ingest_time) AS timestamp),'MM-DD HH24:MI') FROM e_raw_reading");
        return map("total", cells.size(), "latestIngestAt", latestIngest, "categories", categories,
                "cells", cells, "zoneSplit", "A区24 / B区24", "coverage", coverage, "coverageThreshold", 95);
    }

    private double coverage(LocalDate today, Long areaId) {
        String areaJoin = areaId == null ? "" : " AND p.area_id=?";
        long valid = number(jdbc.queryForObject("""
                SELECT COUNT(*) FROM e_raw_reading r JOIN e_meter_point p ON p.point_id=r.point_id
                WHERE r.sample_time>=? AND r.sample_time<?
                  AND r.quality_state IN ('ok','late','est','fix') %s
                """.formatted(areaJoin), Long.class,
                paramsWithArea(areaId, Timestamp.valueOf(today.atStartOfDay()), Timestamp.valueOf(today.plusDays(1).atStartOfDay())))).longValue();
        long expected = number(jdbc.queryForObject("""
                SELECT COALESCE(SUM(FLOOR(86400/sample_period_sec)),0) FROM e_meter_point p
                WHERE status='enabled' %s
                """.formatted(areaJoin), Long.class, paramsWithArea(areaId))).longValue();
        return round(expected == 0 ? 0 : valid * 100 / expected, 2);
    }

    private BigDecimal dayUsage(LocalDate day, String energy, Long areaId) {
        return amount("""
                SELECT COALESCE(SUM(total_value),0) FROM e_stat_day
                WHERE object_type='area' AND stat_date=? AND energy_type_code=?
                """ + objectAreaClause(areaId), paramsWithArea(areaId, Date.valueOf(day), energy));
    }

    private Double baselineDeviation(LocalDate day, String energy, Long areaId) {
        String objectType = areaId == null ? "system" : "area";
        Object objectId = areaId == null ? 0L : areaId;
        List<Double> values = jdbc.query("""
                SELECT baseline_deviation_pct FROM e_stat_day
                WHERE object_type=? AND object_id=? AND stat_date=? AND energy_type_code=?
                """, (rs, rowNum) -> rs.getObject(1) == null ? null : rs.getDouble(1), objectType, objectId, Date.valueOf(day), energy);
        return values.isEmpty() ? null : values.get(0);
    }

    private Long areaId(String zone) {
        if ("ALL".equalsIgnoreCase(zone)) return null;
        if (!"A".equalsIgnoreCase(zone) && !"B".equalsIgnoreCase(zone)) throw new IllegalArgumentException("zone 仅支持 ALL/A/B");
        return jdbc.queryForObject("SELECT area_id FROM e_area WHERE area_code=?", Long.class, "AREA-" + zone.toUpperCase());
    }

    private String energyCode(String energyType) {
        return switch (energyType.toUpperCase()) {
            case "ELEC" -> "electricity";
            case "WATER" -> "water";
            case "AIR" -> "compressed_air";
            default -> throw new IllegalArgumentException("energyType 仅支持 ELEC/WATER/AIR");
        };
    }

    private String areaClause(Long areaId) { return areaId == null ? "" : " AND area_id=?"; }
    private String objectAreaClause(Long areaId) { return areaId == null ? "" : " AND object_id=?"; }
    private String areaClauseForPoint(Long areaId) { return areaId == null ? "" : " AND p.area_id=?"; }

    private Object[] paramsWithArea(Long areaId, Object... values) {
        Object[] params = Arrays.copyOf(values, values.length + (areaId == null ? 0 : 1));
        if (areaId != null) params[values.length] = areaId;
        return params;
    }

    private BigDecimal amount(String sql, Object... params) {
        BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class, params);
        return value == null ? BigDecimal.ZERO : value;
    }

    private int count(String sql, Object... params) {
        Integer value = jdbc.queryForObject(sql, Integer.class, params);
        return value == null ? 0 : value;
    }

    private String scalarString(String sql, Object... params) {
        String value = jdbc.queryForObject(sql, String.class, params);
        return value == null ? "—" : value;
    }

    private Number number(Object value) { return value instanceof Number n ? n : 0; }
    private LocalDateTime dateTime(Object value) {
        if (value instanceof LocalDateTime local) return local;
        if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
        throw new IllegalArgumentException("无法识别的时间值");
    }
    private int precision(String energy) { return "electricity".equals(energy) ? 1 : 2; }
    private Object rounded(BigDecimal value, String energy) { return value.setScale(precision(energy), RoundingMode.HALF_UP); }
    private double percentChange(BigDecimal current, BigDecimal previous) {
        return previous.signum() == 0 ? 0 : round(current.subtract(previous).multiply(BigDecimal.valueOf(100)).divide(previous, 4, RoundingMode.HALF_UP).doubleValue(), 2);
    }
    private double round(double value, int scale) { return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue(); }
    private String signed(double value) { return (value > 0 ? "+" : "") + round(value, 2); }
    private String level(String dbLevel) { return switch (dbLevel) { case "severe" -> "crit"; case "normal" -> "warn"; default -> "info"; }; }
    private String qualityKey(String value) { return switch (value == null ? "miss" : value) { case "ok" -> "ok"; case "late" -> "late"; case "est" -> "est"; case "fix" -> "fix"; default -> "miss"; }; }
    private String qualityLabel(String key) { return switch (key) { case "ok" -> "正常"; case "late" -> "迟到"; case "est" -> "估算"; case "fix" -> "修正"; default -> "缺测/异常"; }; }

    private Map<String, Object> card(String code, String label, String unit, Object value, String tag, String tagKind,
                                     String delta, String deltaKind, String deltaLabel, String subLabel, String req) {
        return map("code", code, "label", label, "unit", unit, "value", value, "tag", tag, "tagKind", tagKind,
                "delta", map("value", delta, "kind", deltaKind, "label", deltaLabel), "subLabel", subLabel, "reqAnchor", req);
    }

    private Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put((String) pairs[i], pairs[i + 1]);
        return result;
    }
}
