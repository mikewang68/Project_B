package com.bdemo.energy.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class RuleEngineService {
    private final JdbcTemplate jdbc;
    private final LocalDateTime now;

    public RuleEngineService(JdbcTemplate jdbc, @Value("${b-demo.demo-now}") LocalDateTime now) {
        this.jdbc = jdbc;
        this.now = now;
    }

    /** REQ-039~044: deterministic R01-R11 evaluation; alert rows are never seeded. */
    @Transactional
    public Map<String, Integer> runAll() {
        jdbc.update("DELETE FROM e_alert_flow_log");
        jdbc.update("DELETE FROM e_alert_event");
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("R01", insert("R01", """
                SELECT 'point' object_type,r.point_id object_id,mp.area_id,MIN(r.sample_time) first_t,MAX(r.sample_time) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('miss_count',SUM(r.quality_state='miss'),'backfill_count',SUM(r.is_backfill=1)) snapshot,NULL level_override
                FROM e_raw_reading r JOIN e_meter_point mp ON mp.point_id=r.point_id
                WHERE (r.quality_state='miss' OR r.is_backfill=1) AND r.sample_time>=DATE_SUB(DATE(?),INTERVAL 30 DAY)
                GROUP BY r.point_id,mp.area_id HAVING COUNT(*)>=3
                """, now));
        counts.put("R02", insert("R02", """
                SELECT 'point' object_type,r.point_id object_id,mp.area_id,MIN(r.sample_time) first_t,MAX(r.sample_time) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('late_count',COUNT(*),'delay_min_threshold',15) snapshot,NULL level_override
                FROM e_raw_reading r JOIN e_meter_point mp ON mp.point_id=r.point_id
                WHERE r.ingest_time IS NOT NULL AND TIMESTAMPDIFF(MINUTE,r.sample_time,r.ingest_time)>15
                  AND r.sample_time>=DATE_SUB(DATE(?),INTERVAL 30 DAY)
                GROUP BY r.point_id,mp.area_id
                """, now));
        counts.put("R03", 0);
        counts.put("R04", insert("R04", """
                SELECT 'point' object_type,r.point_id object_id,mp.area_id,MIN(r.sample_time) first_t,MAX(r.sample_time) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('peak_incremental',MAX(r.incremental_value)) snapshot,NULL level_override
                FROM e_raw_reading r JOIN e_meter_point mp ON mp.point_id=r.point_id
                WHERE r.quality_state='jump' GROUP BY r.point_id,mp.area_id
                """));
        counts.put("R05", insert("R05", """
                WITH workload_types AS (SELECT DISTINCT eq.equipment_type FROM e_equipment eq JOIN e_work_order wo ON wo.equipment_id=eq.equipment_id),
                class_baseline AS (SELECT eq.equipment_type,h.tou_period,AVG(h.total_value) mean_val,COUNT(DISTINCT h.object_id) peers
                  FROM e_stat_hour h JOIN e_equipment eq ON eq.equipment_id=h.object_id JOIN workload_types wt ON wt.equipment_type=eq.equipment_type
                  WHERE h.object_type='equipment' AND h.energy_type_code='electricity' AND h.stat_time>='2026-05-04' AND h.stat_time<'2026-06-29'
                  GROUP BY eq.equipment_type,h.tou_period HAVING peers>=2),
                tagged AS (SELECT h.object_id,h.stat_time,h.total_value,cb.mean_val,
                  (h.total_value>cb.mean_val*1.5 AND NOT EXISTS(SELECT 1 FROM e_work_order wo WHERE wo.equipment_id=h.object_id
                    AND wo.start_time<DATE_ADD(h.stat_time,INTERVAL 1 HOUR) AND ((wo.end_time IS NOT NULL AND wo.end_time>h.stat_time)
                    OR (wo.end_time IS NULL AND wo.start_time>DATE_SUB(h.stat_time,INTERVAL 8 HOUR))))) hit
                  FROM e_stat_hour h JOIN e_equipment eq ON eq.equipment_id=h.object_id
                  JOIN class_baseline cb ON cb.equipment_type=eq.equipment_type AND cb.tou_period=h.tou_period
                  WHERE h.object_type='equipment' AND h.energy_type_code='electricity' AND h.stat_time>='2026-06-29'),
                grouped AS (SELECT *,SUM(IF(hit=0,1,0)) OVER(PARTITION BY object_id ORDER BY stat_time) run_group FROM tagged),
                runs AS (SELECT object_id,COUNT(*) run_len,MIN(stat_time) first_t,MAX(stat_time) last_t,AVG(mean_val) class_mean,MAX(total_value) peak
                  FROM grouped WHERE hit=1 GROUP BY object_id,run_group HAVING COUNT(*)>=3)
                SELECT 'equipment' object_type,r.object_id,e.area_id,MIN(r.first_t) first_t,MAX(r.last_t) last_t,SUM(r.run_len) occur_count,
                  JSON_OBJECT('run_count',COUNT(*),'max_run_len',MAX(r.run_len),'class_mean_avg',AVG(r.class_mean),'peak_val',MAX(r.peak),'multiplier',1.5) snapshot,NULL level_override
                FROM runs r JOIN e_equipment e ON e.equipment_id=r.object_id GROUP BY r.object_id,e.area_id
                """));
        counts.put("R06", insert("R06", """
                WITH work_mean AS (SELECT object_type,object_id,AVG(total_value) work_mean FROM e_stat_hour
                  WHERE object_type IN ('equipment','area') AND energy_type_code='compressed_air' AND tou_period='peak'
                    AND stat_time>='2026-05-04' AND stat_time<'2026-06-29' GROUP BY object_type,object_id HAVING work_mean>0),
                hits AS (SELECT h.object_type,h.object_id,h.stat_time,h.total_value,m.work_mean FROM e_stat_hour h JOIN work_mean m
                  ON m.object_type=h.object_type AND m.object_id=h.object_id WHERE h.energy_type_code='compressed_air' AND h.tou_period='valley'
                    AND h.stat_time>='2026-06-29' AND h.total_value>m.work_mean*0.3)
                SELECT h.object_type,h.object_id,IF(h.object_type='area',h.object_id,e.area_id) area_id,MIN(h.stat_time) first_t,MAX(h.stat_time) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('valley_hits',COUNT(*),'work_pct_threshold',0.3,'work_mean_avg',AVG(h.work_mean)) snapshot,NULL level_override
                FROM hits h LEFT JOIN e_equipment e ON h.object_type='equipment' AND e.equipment_id=h.object_id
                GROUP BY h.object_type,h.object_id,IF(h.object_type='area',h.object_id,e.area_id) HAVING COUNT(*)>=2
                """));
        counts.put("R07", insert("R07", """
                WITH hourly AS (SELECT s.equipment_id,DATE_FORMAT(s.event_time,'%Y-%m-%d %H:00:00') hour_t,COUNT(*) toggles
                  FROM e_equipment_status_log s JOIN e_equipment e ON e.equipment_id=s.equipment_id
                  WHERE e.equipment_type='compressor' AND s.event_type='event' AND s.event_time>='2026-06-29'
                  GROUP BY s.equipment_id,hour_t HAVING toggles>=6)
                SELECT 'equipment' object_type,h.equipment_id object_id,e.area_id,MIN(h.hour_t) first_t,MAX(h.hour_t) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('hit_hours',COUNT(*),'peak_toggles_in_hour',MAX(h.toggles),'threshold',6) snapshot,NULL level_override
                FROM hourly h JOIN e_equipment e ON e.equipment_id=h.equipment_id GROUP BY h.equipment_id,e.area_id
                """));
        counts.put("R08", insert("R08", """
                SELECT 'equipment' object_type,h.object_id,e.area_id,MIN(h.stat_time) first_t,MAX(h.stat_time) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('hit_hours',COUNT(*),'power_kw_threshold',2) snapshot,NULL level_override
                FROM e_stat_hour h JOIN e_equipment e ON e.equipment_id=h.object_id
                WHERE h.object_type='equipment' AND h.energy_type_code='electricity' AND h.tou_period='valley'
                  AND e.equipment_type='lighting' AND h.stat_time>='2026-06-29' AND h.total_value>2
                GROUP BY h.object_id,e.area_id HAVING COUNT(*)>=1
                """));
        counts.put("R09", insert("R09", """
                SELECT 'system' object_type,0 object_id,NULL area_id,TIMESTAMP(s.stat_date) first_t,TIMESTAMP(s.stat_date) last_t,1 occur_count,
                  JSON_OBJECT('stat_date',s.stat_date,'energy_type',s.energy_type_code,'deviation_pct',s.baseline_deviation_pct,'actual_total',s.total_value) snapshot,NULL level_override
                FROM e_stat_day s WHERE s.object_type='system' AND s.object_id=0 AND s.baseline_deviation_pct>20
                """));
        counts.put("R10", insert("R10", """
                WITH report_month AS (SELECT MAX(stat_month) m FROM e_cost_record WHERE object_type='area' AND energy_type_code='electricity'
                  AND stat_month<IF(DAY(?)<15,DATE_FORMAT(?,'%Y-%m'),DATE_FORMAT(DATE_ADD(?,INTERVAL 1 MONTH),'%Y-%m')) AND is_current=1),
                baseline AS (SELECT c.object_id,SUM(c.peak_cost) pc,SUM(c.total_cost) tc FROM e_cost_record c,report_month rm
                  WHERE c.object_type='area' AND c.energy_type_code='electricity' AND c.is_current=1 AND c.stat_month<>rm.m GROUP BY c.object_id),
                report AS (SELECT c.* FROM e_cost_record c,report_month rm WHERE c.object_type='area' AND c.energy_type_code='electricity' AND c.is_current=1 AND c.stat_month=rm.m)
                SELECT 'area' object_type,r.object_id,r.object_id area_id,TIMESTAMP(CONCAT(r.stat_month,'-01 12:00:00')) first_t,TIMESTAMP(CONCAT(r.stat_month,'-01 12:00:00')) last_t,1 occur_count,
                  JSON_OBJECT('report_month',r.stat_month,'baseline_peak_share',b.pc/NULLIF(b.tc,0),'report_peak_share',r.peak_cost/NULLIF(r.total_cost,0),
                    'diff_pp',((r.peak_cost/NULLIF(r.total_cost,0))-(b.pc/NULLIF(b.tc,0)))*100,'threshold_pp',8) snapshot,NULL level_override
                FROM report r JOIN baseline b ON b.object_id=r.object_id
                WHERE ((r.peak_cost/NULLIF(r.total_cost,0))-(b.pc/NULLIF(b.tc,0)))*100>8
                """, now, now, now));
        counts.put("R11", insert("R11", """
                WITH daily AS (SELECT r.point_id,DATE(r.sample_time) d,mp.area_id,
                  SUM(r.quality_state IN ('ok','late','est','fix'))/(86400.0/mp.sample_period_sec) coverage
                  FROM e_raw_reading r JOIN e_meter_point mp ON mp.point_id=r.point_id
                  WHERE mp.energy_type_code IS NOT NULL AND r.sample_time>=DATE_SUB(DATE(?),INTERVAL 30 DAY)
                  GROUP BY r.point_id,DATE(r.sample_time),mp.area_id,mp.sample_period_sec),
                bad AS (SELECT * FROM daily WHERE coverage<0.95)
                SELECT 'point' object_type,point_id object_id,area_id,TIMESTAMP(MIN(d)) first_t,TIMESTAMP(MAX(d)) last_t,COUNT(*) occur_count,
                  JSON_OBJECT('days_below_threshold',COUNT(*),'min_coverage',MIN(coverage),'warn_threshold',0.95,'severe_threshold',0.8) snapshot,
                  IF(MIN(coverage)<0.8,'severe',NULL) level_override
                FROM bad GROUP BY point_id,area_id
                """, now));
        return counts;
    }

    private int insert(String code, String select, Object... args) {
        String sql = """
                INSERT INTO e_alert_event(rule_id,rule_code,rule_version_no,object_type,object_id,area_id,level,status,
                  first_occur_time,last_occur_time,occur_count,snapshot_json,notification_json,create_time,update_time)
                SELECT r.rule_id,r.rule_code,r.version_no,x.object_type,x.object_id,x.area_id,COALESCE(x.level_override,r.level),'new',
                  x.first_t,x.last_t,x.occur_count,x.snapshot,
                  JSON_ARRAY(JSON_OBJECT('channel','in_app','targetRole',IF(r.rule_category='quality','ops','energy_mgr'),'sentAt',?,'result','success','retryCount',0)),?,?
                FROM e_alert_rule r JOIN (%s) x WHERE r.rule_code=? AND r.enabled=1
                """.formatted(select);
        Object[] all = new Object[args.length + 4];
        all[0] = now; all[1] = now; all[2] = now;
        System.arraycopy(args, 0, all, 3, args.length);
        all[all.length - 1] = code;
        return jdbc.update(sql, all);
    }
}
