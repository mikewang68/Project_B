package com.bdemo.energy.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class BaselinePipelineService {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final LocalDateTime demoNow;

    public BaselinePipelineService(JdbcTemplate jdbc, ObjectMapper json,
                                   @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc;
        this.json = json;
        this.demoNow = demoNow;
    }

    /** REQ-029: publish six workday/weekend x TOU buckets and refresh report deviations. */
    @Transactional
    public Map<String, Object> publish(boolean force) {
        String filter = force ? "status IN ('draft','published')" : "status='draft'";
        List<Map<String, Object>> baselines = jdbc.queryForList("SELECT * FROM e_energy_baseline WHERE " + filter + " ORDER BY baseline_id");
        var published = new ArrayList<Map<String, Object>>();
        var skipped = new ArrayList<String>();
        for (Map<String, Object> baseline : baselines) {
            long id = ((Number) baseline.get("baseline_id")).longValue();
            boolean area = "area".equals(String.valueOf(baseline.get("object_scope")));
            String sql = """
                    SELECT IF(WEEKDAY(stat_time)<5,'workday','weekend') day_kind,tou_period,
                      AVG(hour_total) mean_value,STDDEV_SAMP(hour_total) sigma,COUNT(*) samples
                    FROM (SELECT stat_time,tou_period,SUM(total_value) hour_total FROM e_stat_hour
                      WHERE object_type='area' AND energy_type_code=? AND stat_time>=? AND stat_time<? %s
                      GROUP BY stat_time,tou_period) h GROUP BY day_kind,tou_period
                    """.formatted(area ? "AND object_id=?" : "");
            Object[] args = area
                    ? new Object[]{baseline.get("energy_type_code"), baseline.get("baseline_start"), baseline.get("baseline_end"), baseline.get("object_id")}
                    : new Object[]{baseline.get("energy_type_code"), baseline.get("baseline_start"), baseline.get("baseline_end")};
            List<Map<String, Object>> rows = jdbc.queryForList(sql, args);
            if (rows.isEmpty()) {
                skipped.add(String.valueOf(baseline.get("baseline_code")));
                continue;
            }
            Map<String, Object> buckets = new LinkedHashMap<>();
            for (Map<String, Object> row : rows) {
                buckets.put(row.get("day_kind") + "_" + row.get("tou_period"), Map.of(
                        "mean", round(((Number) row.get("mean_value")).doubleValue()),
                        "sigma", round(row.get("sigma") == null ? 0 : ((Number) row.get("sigma")).doubleValue()),
                        "samples", ((Number) row.get("samples")).intValue()));
            }
            jdbc.update("UPDATE e_energy_baseline SET buckets_json=?,status='published',published_at=?,published_by='spring-pipeline',formula_version=COALESCE(formula_version,'f-1.3'),update_time=? WHERE baseline_id=?",
                    writeJson(buckets), demoNow, demoNow, id);
            published.add(Map.of("code", baseline.get("baseline_code"), "buckets", buckets));
        }
        int deviations = refreshDeviations();
        return Map.of("published", published, "skipped_no_data", skipped, "force_republish", force, "deviation", deviations);
    }

    private int refreshDeviations() {
        int updates = 0;
        for (Map<String, Object> b : jdbc.queryForList("SELECT * FROM e_energy_baseline WHERE status='published' AND buckets_json IS NOT NULL")) {
            Map<?, ?> buckets;
            try { buckets = json.readValue(String.valueOf(b.get("buckets_json")), Map.class); }
            catch (JsonProcessingException ex) { throw new IllegalStateException("基线桶 JSON 无效: " + b.get("baseline_code"), ex); }
            String objectType = "area".equals(b.get("object_scope")) ? "area" : "system";
            long objectId = "area".equals(objectType) ? ((Number) b.get("object_id")).longValue() : 0L;
            LocalDate start = ((java.sql.Date) b.get("baseline_end")).toLocalDate().plusDays(1);
            List<Map<String, Object>> days = jdbc.queryForList("SELECT stat_date,total_value,workday_flag FROM e_stat_day WHERE object_type=? AND object_id=? AND energy_type_code=? AND stat_date>=? AND stat_date<=?",
                    objectType, objectId, b.get("energy_type_code"), start, demoNow.toLocalDate());
            for (Map<String, Object> day : days) {
                Object flag = day.get("workday_flag");
                boolean workday = flag instanceof Boolean boolFlag ? boolFlag : ((Number) flag).intValue() == 1;
                String prefix = workday ? "workday_" : "weekend_";
                double expected = mean(buckets, prefix + "peak") * 6 + mean(buckets, prefix + "flat") * 10 + mean(buckets, prefix + "valley") * 8;
                if (expected <= 0) continue;
                double actual = ((Number) day.get("total_value")).doubleValue();
                updates += jdbc.update("UPDATE e_stat_day SET baseline_id=?,baseline_deviation_pct=? WHERE object_type=? AND object_id=? AND energy_type_code=? AND stat_date=?",
                        b.get("baseline_id"), round((actual - expected) / expected * 100), objectType, objectId,
                        b.get("energy_type_code"), day.get("stat_date"));
            }
        }
        return updates;
    }

    private double mean(Map<?, ?> buckets, String key) {
        Object value = buckets.get(key);
        if (!(value instanceof Map<?, ?> bucket) || !(bucket.get("mean") instanceof Number n)) return 0;
        return n.doubleValue();
    }

    private String writeJson(Object value) {
        try { return json.writeValueAsString(value); }
        catch (JsonProcessingException ex) { throw new IllegalStateException(ex); }
    }

    private double round(double value) { return Math.round(value * 10_000d) / 10_000d; }
}
