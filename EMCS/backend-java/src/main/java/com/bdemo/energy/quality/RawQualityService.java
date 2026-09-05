package com.bdemo.energy.quality;

import com.bdemo.common.BusinessException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
public class RawQualityService {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LocalDateTime demoNow;

    public RawQualityService(JdbcTemplate jdbc, ObjectMapper json,
                             @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc; this.json = json; this.demoNow = demoNow;
    }

    public Map<String, Object> summary(String pointCode, String timeStart, String timeEnd, String zone) {
        String selectedCode = blank(pointCode) ? "GC-A1-E" : pointCode;
        LocalDateTime start = blank(timeStart) ? LocalDateTime.of(2026, 7, 6, 6, 0) : parse(timeStart);
        LocalDateTime end = blank(timeEnd) ? LocalDateTime.of(2026, 7, 6, 18, 0) : parse(timeEnd);
        if (!start.isBefore(end)) throw new IllegalArgumentException("timeStart 必须早于 timeEnd");
        List<Map<String, Object>> selectedRows = pointRows(" AND p.point_code=?", selectedCode);
        if (selectedRows.isEmpty()) throw new BusinessException(404, "采集点不存在");
        Map<String, Object> selected = selectedRows.get(0);
        String zoneSql = "ALL".equalsIgnoreCase(zone) ? "" : " AND a.area_code=?";
        Object[] pointParams = zoneSql.isEmpty() ? new Object[0] : new Object[]{"AREA-" + zone.toUpperCase()};
        List<Map<String, Object>> points = pointRows(zoneSql, pointParams).stream().map(this::point).toList();
        List<Map<String, Object>> readings = jdbc.queryForList("""
                SELECT r.sample_time,r.cumulative_value,r.incremental_value,r.quality_state,r.source_batch,
                  r.ingest_time,r.is_backfill,r.correction_reason,r.status_value
                FROM e_raw_reading r JOIN e_meter_point p ON p.point_id=r.point_id
                WHERE p.point_code=? AND r.sample_time>=? AND r.sample_time<? ORDER BY r.sample_time
                """, selectedCode, Timestamp.valueOf(start), Timestamp.valueOf(end)).stream().map(row -> map(
                "ts", format(row.get("sample_time")), "cumulative", row.get("cumulative_value"), "delta", row.get("incremental_value"),
                "unit", selected.get("unit"), "quality", row.get("quality_state"), "sourceBatchId", row.get("source_batch"),
                "ingestedAt", format(row.get("ingest_time")), "isBackfill", bool(row.get("is_backfill")),
                "remark", row.get("correction_reason"), "statusValue", row.get("status_value"))).toList();
        long expected = Math.max(1, java.time.Duration.between(start, end).getSeconds() / ((Number) selected.get("sample_period_sec")).intValue());
        long valid = readings.stream().filter(row -> List.of("ok", "late", "est", "fix").contains(row.get("quality"))).count();
        double coverage = round(valid * 100.0 / expected, 2);
        Map<String, Object> selectedView = map("pointId", selected.get("point_code"), "pointName", selected.get("point_name"),
                "deviceId", selected.get("equipment_id"), "deviceName", fallback(selected.get("equipment_name"), selected.get("point_name")),
                "zone", String.valueOf(selected.get("area_code")).replace("AREA-", ""),
                "energyType", energyUi(selected.get("energy_type_code")), "unit", selected.get("unit"),
                "samplingInterval", interval(selected.get("sample_period_sec")), "currentTaskState", currentTask(selected.get("area_id")),
                "coverageToday", todayCoverage(((Number) selected.get("point_id")).longValue(), ((Number) selected.get("sample_period_sec")).intValue()));
        return map("signature", signature(coverage), "demoState", map("enabled", true,
                        "hint", "第二幕 · 原始数据与质量演示态：断传→补传→重算主案例", "reqAnchor", "REQ-014 / 019 / 020"),
                "filters", map("pointId", selectedCode, "timeStart", format(start), "timeEnd", format(end), "zone", zone),
                "coverage", map("pct", coverage, "threshold", map("warn", 95, "serious", 80),
                        "band", qualityBand(coverage / 100), "missingSlotCount", Math.max(0, expected - valid)),
                "selected", selectedView, "points", points, "readings", readings,
                "tasks", tasks(selected.get("area_id")), "backfillBatches", backfills(selected.get("area_id")),
                "recomputeHints", recomputeHints(), "qualityBreakdown", breakdown(readings));
    }

    @Transactional
    public Map<String, Object> backfill(RawQualityController.BackfillRequest body, String operator) {
        if (blank(body.pointId()) || blank(body.cacheStart()) || blank(body.cacheEnd())) throw new IllegalArgumentException("补传参数不完整");
        LocalDateTime start = parse(body.cacheStart()), end = parse(body.cacheEnd());
        if (!start.isBefore(end)) throw new IllegalArgumentException("cacheStart 必须早于 cacheEnd");
        List<Map<String, Object>> points = pointRows(" AND p.point_code=?", body.pointId());
        if (points.isEmpty()) throw new BusinessException(404, "采集点不存在");
        Map<String, Object> point = points.get(0); long pointId = ((Number) point.get("point_id")).longValue();
        Integer affected = jdbc.queryForObject("""
                SELECT COUNT(*) FROM e_raw_reading WHERE point_id=? AND sample_time>=? AND sample_time<?
                  AND quality_state IN ('miss','late')
                """, Integer.class, pointId, Timestamp.valueOf(start), Timestamp.valueOf(end));
        String batchId = "BACKFILL-" + start.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmm"));
        List<String> periods = List.of(start.toLocalDate() + "D", start.toString().substring(0, 7) + "M");
        Map<String, Object> batch = map("batchId", batchId, "triggeredBy", operator, "triggeredAt", format(demoNow),
                "cacheStart", format(start), "cacheEnd", format(end), "pointIds", List.of(body.pointId()),
                "recordsIngested", affected == null ? 0 : affected, "dupHandledCount", 0,
                "failureReason", "人工触发补传", "status", "succeeded", "affectedPeriods", periods);
        if (Boolean.TRUE.equals(body.dryRun())) return map("batch", batch, "derivedRecomputeHints", periods.stream().map(this::previewHint).toList());
        jdbc.update("""
                UPDATE e_raw_reading SET quality_state='fix',is_backfill=1,source_batch=?,ingest_time=?,correction_reason='人工补传修正'
                WHERE point_id=? AND sample_time>=? AND sample_time<? AND quality_state IN ('miss','late')
                """, batchId, Timestamp.valueOf(demoNow), pointId, Timestamp.valueOf(start), Timestamp.valueOf(end));
        jdbc.update("""
                INSERT INTO e_collect_task(batch_no,area_id,point_scope,scheduled_time,actual_ingest_time,task_status,
                  affected_point_count,failure_reason,retry_count,outage_start,outage_end,create_time)
                VALUES(?,?,?,?,?,'backfilled',?,'人工触发补传',0,?,?,?)
                """, batchId, point.get("area_id"), body.pointId(), Timestamp.valueOf(start), Timestamp.valueOf(demoNow), 1,
                Timestamp.valueOf(start), Timestamp.valueOf(end), Timestamp.valueOf(demoNow));
        List<Map<String, Object>> hints = new ArrayList<>();
        for (String period : periods) {
            String target = period.endsWith("D") ? "e_stat_day" : "e_stat_month";
            String key = toJson(map("periodKey", period, "pointCode", body.pointId()));
            jdbc.update("""
                    INSERT INTO e_recompute_log(target_table,target_key_json,old_version_no,trigger_reason,operator,created_at)
                    VALUES(?,?,1,'backfill',?,?)
                    """, target, key, operator, Timestamp.valueOf(demoNow));
            hints.add(previewHint(period));
        }
        return map("batch", batch, "derivedRecomputeHints", hints);
    }

    @Transactional
    public Map<String, Object> recompute(RawQualityController.RecomputeRequest body, String operator) {
        if (blank(body.periodKey()) || !("day".equals(body.scope()) || "month".equals(body.scope())))
            throw new IllegalArgumentException("periodKey/scope 无效");
        String target = "day".equals(body.scope()) ? "e_stat_day" : "e_stat_month";
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT * FROM e_recompute_log WHERE target_table=? AND target_key_json LIKE ? AND new_version_no IS NULL
                ORDER BY id DESC LIMIT 1 FOR UPDATE
                """, target, "%\"periodKey\":\"" + body.periodKey() + "\"%");
        if (rows.isEmpty()) throw new BusinessException(404, "periodKey 不存在或已重算");
        Map<String, Object> row = rows.get(0); int oldVersion = ((Number) fallback(row.get("old_version_no"), 1)).intValue();
        jdbc.update("UPDATE e_recompute_log SET new_version_no=?,delta_value=0,delta_pct=0,operator=? WHERE id=?",
                oldVersion + 1, operator, row.get("id"));
        return map("id", row.get("id"), "periodKey", body.periodKey(), "scope", body.scope(), "status", "completed",
                "oldVersionNo", oldVersion, "newVersionNo", oldVersion + 1,
                "diffSummary", map("deltaValue", 0, "deltaPct", 0), "operator", operator, "completedAt", format(demoNow));
    }

    private List<Map<String, Object>> pointRows(String extra, Object... params) { return jdbc.queryForList("""
            SELECT p.*,a.area_code,a.area_name,e.equipment_code,e.equipment_name FROM e_meter_point p
            JOIN e_area a ON a.area_id=p.area_id LEFT JOIN e_equipment e ON e.equipment_id=p.equipment_id
            WHERE 1=1 %s ORDER BY a.area_code,p.point_id
            """.formatted(extra), params); }
    private Map<String, Object> point(Map<String, Object> row) { return map("zone", String.valueOf(row.get("area_code")).replace("AREA-", ""),
            "deviceId", fallback(row.get("equipment_code"), row.get("point_code")), "deviceName", fallback(row.get("equipment_name"), row.get("point_name")),
            "energyType", energyUi(row.get("energy_type_code")), "pointId", row.get("point_code"), "pointName", row.get("point_name"),
            "unit", row.get("unit"), "samplingInterval", interval(row.get("sample_period_sec")),
            "status", "enabled".equals(row.get("status")) ? "启用" : "停用"); }
    private List<Map<String, Object>> tasks(Object areaId) { return jdbc.queryForList("""
            SELECT * FROM e_collect_task WHERE area_id=? OR area_id IS NULL ORDER BY scheduled_time DESC LIMIT 12
            """, areaId).stream().map(row -> map("taskId", row.get("batch_no"), "taskName", fallback(row.get("point_scope"), "ALL"),
            "dataSource", "gateway", "lastSuccessAt", format(row.get("actual_ingest_time")), "lastFailureAt", null,
            "lastError", row.get("failure_reason"), "failureBatchCount", row.get("retry_count"),
            "currentState", taskState(row.get("task_status")), "affectedPoints", split(row.get("point_scope")),
            "samplingInterval", "15min", "timeoutThreshold", "45min")).toList(); }
    private List<Map<String, Object>> backfills(Object areaId) { return jdbc.queryForList("""
            SELECT * FROM e_collect_task WHERE (area_id=? OR area_id IS NULL) AND (task_status='backfilled' OR outage_start IS NOT NULL)
            ORDER BY actual_ingest_time DESC LIMIT 10
            """, areaId).stream().map(row -> map("batchId", row.get("batch_no"), "triggeredBy", "system",
            "triggeredAt", format(row.get("actual_ingest_time")), "cacheStart", format(row.get("outage_start")),
            "cacheEnd", format(row.get("outage_end")), "pointIds", split(row.get("point_scope")),
            "recordsIngested", row.get("affected_point_count"), "dupHandledCount", 0,
            "failureReason", row.get("failure_reason"), "status", "succeeded", "affectedPeriods", periods(row.get("outage_start")))).toList(); }
    private List<Map<String, Object>> recomputeHints() { return jdbc.queryForList("SELECT * FROM e_recompute_log WHERE new_version_no IS NULL ORDER BY created_at DESC LIMIT 20")
            .stream().map(row -> { Map<String, Object> key = jsonObject(row.get("target_key_json")); return map("id", row.get("id"),
                    "periodKey", fallback(key.get("periodKey"), key.get("period_key")), "scope", String.valueOf(row.get("target_table")).endsWith("day") ? "day" : "month",
                    "status", "pending", "reason", row.get("trigger_reason"), "createdAt", format(row.get("created_at"))); }).toList(); }
    private List<Map<String, Object>> breakdown(List<Map<String, Object>> readings) { List<Map<String, Object>> out = new ArrayList<>(); for (String quality : List.of("ok", "miss", "late", "est", "fix")) { long count = readings.stream().filter(row -> quality.equals(row.get("quality"))).count(); if (count > 0) out.add(map("quality", quality, "count", count, "pct", round(count * 100.0 / readings.size(), 1), "sampleTs", readings.stream().filter(row -> quality.equals(row.get("quality"))).map(row -> row.get("ts")).findFirst().orElse(null))); } return out; }
    private Map<String, Object> previewHint(String period) { return map("periodKey", period, "scope", period.endsWith("D") ? "day" : "month", "status", "pending", "reason", "backfill"); }
    private List<String> periods(Object value) { if (value == null) return List.of(); LocalDate day = dateTime(value).toLocalDate(); return List.of(day + "D", day.toString().substring(0, 7) + "M"); }
    private double todayCoverage(long pointId, int sampleSeconds) { Long count = jdbc.queryForObject("SELECT COUNT(*) FROM e_raw_reading WHERE point_id=? AND sample_time>=? AND sample_time<? AND quality_state IN ('ok','late','est','fix')", Long.class, pointId, Timestamp.valueOf(demoNow.toLocalDate().atStartOfDay()), Timestamp.valueOf(demoNow.toLocalDate().plusDays(1).atStartOfDay())); return round((count == null ? 0 : count) * 100.0 / Math.max(1, 86400 / sampleSeconds), 2); }
    private String currentTask(Object areaId) { List<String> values = jdbc.query("SELECT task_status FROM e_collect_task WHERE area_id=? OR area_id IS NULL ORDER BY scheduled_time DESC LIMIT 1", (rs, n) -> rs.getString(1), areaId); return values.isEmpty() ? "unknown" : taskState(values.get(0)); }
    private Map<String, Object> signature(double coverage) { return map("version", "v0.3.4-demo", "formulaVersion", "v0.1", "priceVersion", "v2026-05", "baselineVersion", "BASELINE-SYSTEM-ELEC-2026", "sigId", Integer.toHexString(Double.valueOf(coverage).hashCode()), "seed", 42, "generatedAt", format(demoNow), "coverage", coverage); }
    private String energyUi(Object value) { return switch (String.valueOf(value)) { case "electricity" -> "ELEC"; case "water" -> "WATER"; case "compressed_air" -> "AIR"; default -> String.valueOf(value); }; }
    private String interval(Object seconds) { return (((Number) seconds).intValue() / 60) + "min"; }
    private String qualityBand(double ratio) { return ratio >= .95 ? "ok" : ratio >= .8 ? "degraded" : "insufficient"; }
    private String taskState(Object value) { return switch (String.valueOf(value)) { case "success", "backfilled" -> "running"; case "failed" -> "failed"; default -> String.valueOf(value); }; }
    private boolean bool(Object value) { return value instanceof Boolean b ? b : value instanceof Number n && n.intValue() != 0; }
    private List<String> split(Object value) { return value == null || value.toString().isBlank() || "ALL".equals(value) ? List.of() : Arrays.stream(value.toString().split(",")).map(String::trim).toList(); }
    private Object fallback(Object value, Object alternate) { return value == null ? alternate : value; }
    private boolean blank(String value) { return value == null || value.isBlank(); }
    private LocalDateTime parse(String value) { return LocalDateTime.parse(value.replace('T', ' '), DT); }
    private LocalDateTime dateTime(Object value) { return value instanceof LocalDateTime local ? local : ((Timestamp) value).toLocalDateTime(); }
    private String format(Object value) { return value == null ? null : (value instanceof LocalDateTime local ? local : dateTime(value)).format(DT); }
    private double round(double value, int scale) { return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP).doubleValue(); }
    private Map<String, Object> jsonObject(Object value) { if (value == null) return Map.of(); try { return json.readValue(value.toString(), new TypeReference<>() {}); } catch (Exception ignored) { return Map.of(); } }
    private String toJson(Object value) { try { return json.writeValueAsString(value); } catch (Exception e) { throw new IllegalArgumentException("JSON 序列化失败"); } }
    private Map<String, Object> map(Object... pairs) { Map<String, Object> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put((String) pairs[i], pairs[i + 1]); return out; }
}
