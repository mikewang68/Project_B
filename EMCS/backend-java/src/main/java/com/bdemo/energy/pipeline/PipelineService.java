package com.bdemo.energy.pipeline;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PipelineService {
    private static final String VALID = "'ok','late','est','fix'";
    private static final String TOU = "CASE WHEN EXTRACT(HOUR FROM CAST(r.sample_time AS timestamp)) IN (8,9,10,18,19,20) THEN 'peak' "
            + "WHEN EXTRACT(HOUR FROM CAST(r.sample_time AS timestamp)) IN (22,23,0,1,2,3,4,5) THEN 'valley' ELSE 'flat' END";
    private static final String AREA_MAIN = "((p.energy_type_code='electricity' AND p.point_category='area_meter') "
            + "OR (p.energy_type_code='compressed_air' AND p.equipment_id IS NULL "
            + "AND (p.point_code LIKE '%-MAIN' OR p.point_code LIKE '%-BRANCH')) "
            + "OR (p.energy_type_code='water' AND p.equipment_id IS NULL AND p.point_code LIKE '%-MAIN'))";

    private final JdbcTemplate jdbc;
    private final BaselinePipelineService baselines;
    private final CostInitializationService costs;
    private final RuleEngineService rules;

    public PipelineService(JdbcTemplate jdbc, BaselinePipelineService baselines,
                           CostInitializationService costs, RuleEngineService rules) {
        this.jdbc = jdbc;
        this.baselines = baselines;
        this.costs = costs;
        this.rules = rules;
    }

    /** REQ-024~028: rebuild all materialized statistics from raw readings. */
    @Transactional
    public Map<String, Integer> rebuildAll() {
        jdbc.update("DELETE FROM e_stat_month");
        jdbc.update("DELETE FROM e_stat_day");
        jdbc.update("DELETE FROM e_stat_hour");
        return rebuild(null, null);
    }

    /** REQ-020: idempotent, versioned rebuild for a bounded half-open time window. */
    @Transactional
    public Map<String, Integer> rebuildRange(LocalDateTime start, LocalDateTime end) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw new IllegalArgumentException("start 必须早于 end");
        }
        return rebuild(start, end);
    }

    private Map<String, Integer> rebuild(LocalDateTime start, LocalDateTime end) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("stat_hour_equipment", update(hourSql("equipment", start != null), start, end));
        counts.put("stat_hour_area", update(hourSql("area", start != null), start, end));
        counts.put("stat_day_equipment", jdbc.update(daySql("equipment", start != null), args(start, end)));
        counts.put("stat_day_area", jdbc.update(daySql("area", start != null), args(start, end)));
        counts.put("stat_day_system", jdbc.update(systemDaySql(start != null), args(start, end)));
        counts.put("stat_month_equipment", jdbc.update(monthSql("equipment", start != null), args(start, end)));
        counts.put("stat_month_area", jdbc.update(monthSql("area", start != null), args(start, end)));
        counts.put("stat_month_system", jdbc.update(monthSql("system", start != null), args(start, end)));
        return counts;
    }

    private int update(String sql, LocalDateTime start, LocalDateTime end) {
        return jdbc.update(sql, args(start, end));
    }

    private Object[] args(LocalDateTime start, LocalDateTime end) {
        return start == null ? new Object[0] : new Object[]{start, end};
    }

    private String hourSql(String scope, boolean ranged) {
        boolean equipment = "equipment".equals(scope);
        String id = equipment ? "p.equipment_id" : "p.area_id";
        String scopeFilter = equipment ? "p.equipment_id IS NOT NULL" : AREA_MAIN;
        String time = ranged ? " AND r.sample_time>=? AND r.sample_time<?" : "";
        return """
                INSERT INTO e_stat_hour(object_type,object_id,energy_type_code,stat_time,total_value,
                  avg_power_kw,coverage_ratio,quality_summary,tou_period,version_no,computed_at)
                SELECT '%s',%s,p.energy_type_code,date_trunc('hour',CAST(r.sample_time AS timestamp)),
                  SUM(CASE WHEN r.quality_state IN (%s) THEN r.incremental_value ELSE 0 END),
                  CASE WHEN p.energy_type_code='electricity' THEN
                    SUM(CASE WHEN r.quality_state IN (%s) THEN r.incremental_value ELSE 0 END) END,
                  LEAST(1,SUM(CASE WHEN r.quality_state IN (%s) THEN 1 ELSE 0 END)/NULLIF(COUNT(DISTINCT r.point_id)*(3600.0/MIN(p.sample_period_sec)),0)),
                  CONCAT('ok=',SUM(CASE WHEN r.quality_state='ok' THEN 1 ELSE 0 END),' late=',SUM(CASE WHEN r.quality_state='late' THEN 1 ELSE 0 END),
                    ' miss=',SUM(CASE WHEN r.quality_state='miss' THEN 1 ELSE 0 END),' est=',SUM(CASE WHEN r.quality_state='est' THEN 1 ELSE 0 END),' fix=',SUM(CASE WHEN r.quality_state='fix' THEN 1 ELSE 0 END)),
                  %s,1,NOW()
                FROM e_raw_reading r JOIN e_meter_point p ON p.point_id=r.point_id
                WHERE p.status='enabled' AND p.energy_type_code IS NOT NULL AND %s %s
                GROUP BY %s,p.energy_type_code,date_trunc('hour',CAST(r.sample_time AS timestamp)),%s
                ON DUPLICATE KEY UPDATE total_value=VALUES(total_value),avg_power_kw=VALUES(avg_power_kw),
                  coverage_ratio=VALUES(coverage_ratio),quality_summary=VALUES(quality_summary),
                  tou_period=VALUES(tou_period),version_no=version_no+1,computed_at=NOW()
                """.formatted(scope, id, VALID, VALID, VALID, TOU, scopeFilter, time, id, TOU);
    }

    private String daySql(String scope, boolean ranged) {
        String time = ranged ? " AND stat_time>=? AND stat_time<?" : "";
        return """
                INSERT INTO e_stat_day(object_type,object_id,energy_type_code,stat_date,total_value,
                  peak_value,flat_value,valley_value,coverage_ratio,workday_flag,version_no,computed_at)
                SELECT object_type,object_id,energy_type_code,CAST(stat_time AS date),SUM(total_value),
                  SUM((CASE WHEN tou_period='peak' THEN total_value ELSE 0 END)),SUM((CASE WHEN tou_period='flat' THEN total_value ELSE 0 END)),
                  SUM((CASE WHEN tou_period='valley' THEN total_value ELSE 0 END)),AVG(coverage_ratio),
                  (CASE WHEN (EXTRACT(ISODOW FROM CAST(CAST(MIN(stat_time) AS date) AS timestamp))-1)<5 THEN 1 ELSE 0 END),1,NOW()
                FROM e_stat_hour WHERE object_type='%s' %s
                GROUP BY object_type,object_id,energy_type_code,CAST(stat_time AS date)
                ON DUPLICATE KEY UPDATE total_value=VALUES(total_value),peak_value=VALUES(peak_value),
                  flat_value=VALUES(flat_value),valley_value=VALUES(valley_value),coverage_ratio=VALUES(coverage_ratio),
                  workday_flag=VALUES(workday_flag),version_no=version_no+1,computed_at=NOW()
                """.formatted(scope, time);
    }

    private String systemDaySql(boolean ranged) {
        String time = ranged ? " AND stat_date>=CAST(? AS date) AND stat_date<CAST(? AS date)" : "";
        return """
                INSERT INTO e_stat_day(object_type,object_id,energy_type_code,stat_date,total_value,
                  peak_value,flat_value,valley_value,coverage_ratio,workday_flag,version_no,computed_at)
                SELECT 'system',0,energy_type_code,stat_date,SUM(total_value),SUM(peak_value),SUM(flat_value),
                  SUM(valley_value),AVG(coverage_ratio),MAX(workday_flag),1,NOW()
                FROM e_stat_day WHERE object_type='area' %s GROUP BY energy_type_code,stat_date
                ON DUPLICATE KEY UPDATE total_value=VALUES(total_value),peak_value=VALUES(peak_value),
                  flat_value=VALUES(flat_value),valley_value=VALUES(valley_value),coverage_ratio=VALUES(coverage_ratio),
                  workday_flag=VALUES(workday_flag),version_no=version_no+1,computed_at=NOW()
                """.formatted(time);
    }

    private String monthSql(String scope, boolean ranged) {
        String time = ranged ? " AND stat_date>=CAST(? AS date) AND stat_date<CAST(? AS date)" : "";
        return """
                INSERT INTO e_stat_month(object_type,object_id,energy_type_code,stat_month,total_value,
                  peak_value,flat_value,valley_value,coverage_ratio,version_no,computed_at)
                SELECT object_type,object_id,energy_type_code,to_char(CAST(stat_date AS timestamp),'YYYY-MM'),SUM(total_value),
                  SUM(peak_value),SUM(flat_value),SUM(valley_value),AVG(coverage_ratio),1,NOW()
                FROM e_stat_day WHERE object_type='%s' %s
                GROUP BY object_type,object_id,energy_type_code,to_char(CAST(stat_date AS timestamp),'YYYY-MM')
                ON DUPLICATE KEY UPDATE total_value=VALUES(total_value),peak_value=VALUES(peak_value),
                  flat_value=VALUES(flat_value),valley_value=VALUES(valley_value),coverage_ratio=VALUES(coverage_ratio),
                  version_no=version_no+1,computed_at=NOW()
                """.formatted(scope, time);
    }

    public Map<String, Object> baselineStatus(boolean forceRepublish) {
        return baselines.publish(forceRepublish);
    }

    public Map<String, Integer> ruleStatus() {
        return rules.runAll();
    }

    public Map<String, Object> costIntegrity() {
        return costs.initializeAndCheck();
    }
}
