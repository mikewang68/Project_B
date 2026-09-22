package com.bdemo.energy.pipeline;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class CostInitializationService {
    private final JdbcTemplate jdbc;
    private final LocalDateTime demoNow;

    public CostInitializationService(JdbcTemplate jdbc, @Value("${b-demo.demo-now}") LocalDateTime demoNow) {
        this.jdbc = jdbc;
        this.demoNow = demoNow;
    }

    /** REQ-051/052/062: create immutable reviewed v1 from every monthly statistic exactly once. */
    @Transactional
    public Map<String, Object> initializeAndCheck() {
        Integer existing = jdbc.queryForObject("SELECT COUNT(*) FROM e_cost_record", Integer.class);
        int created = 0;
        if (existing == null || existing == 0) {
            jdbc.update("""
                    INSERT INTO e_cost_record(object_type,object_id,stat_month,energy_type_code,cost_version,is_current,
                      usage_qty,peak_qty,flat_qty,valley_qty,peak_cost,flat_cost,valley_cost,total_cost,
                      tariff_version_no,alloc_rule_version_no,formula_version,tariff_snapshot_json,
                      alloc_rule_snapshot_json,source_stat_snapshot_json,status,reviewed_at,signature,computed_by,computed_at)
                    SELECT s.object_type,s.object_id,s.stat_month,s.energy_type_code,1,1,s.total_value,s.peak_value,s.flat_value,s.valley_value,
                      CASE WHEN s.energy_type_code='electricity' THEN s.peak_value*COALESCE(tp.price,0) ELSE 0 END,
                      CASE WHEN s.energy_type_code='electricity' THEN s.flat_value*COALESCE(tf.price,0) ELSE s.total_value*COALESCE(to1.price,0) END,
                      CASE WHEN s.energy_type_code='electricity' THEN s.valley_value*COALESCE(tv.price,0) ELSE 0 END,
                      CASE WHEN s.energy_type_code='electricity' THEN s.peak_value*COALESCE(tp.price,0)+s.flat_value*COALESCE(tf.price,0)+s.valley_value*COALESCE(tv.price,0)
                           ELSE s.total_value*COALESCE(to1.price,0) END,
                      CASE WHEN s.energy_type_code='electricity' THEN CONCAT('peak_v',tp.version_no,'|flat_v',tf.version_no,'|valley_v',tv.version_no)
                           ELSE CONCAT('flat_only_v',to1.version_no) END,
                      CONCAT('alloc-v',ar.version_no),'COST-V1',
                      json_build_array(json_build_object('energyType',s.energy_type_code,'currency','CNY','versionNo',COALESCE(tp.version_no,to1.version_no,1))),
                      json_build_object('ruleId',ar.rule_id,'ruleName',ar.rule_name,'scope',ar.scope,'method',ar.method,'config',ar.config_json,'versionNo',ar.version_no),
                      json_build_object('table','e_stat_month','recordId',s.id,'version',s.version_no,'qualifiedUsageQty',s.total_value,'quality',json_build_object('coverageRatio',s.coverage_ratio)),
                      'reviewed',?,'pending',
                      'spring-pipeline',?
                    FROM e_stat_month s
                    LEFT JOIN e_tariff_version tp ON tp.energy_type_code=s.energy_type_code AND tp.tou_period='peak' AND tp.effective_from<=to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AND (tp.effective_to IS NULL OR tp.effective_to>=CAST(date_trunc('month',CAST(to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AS timestamp)) + INTERVAL '1 month' - INTERVAL '1 day' AS date))
                    LEFT JOIN e_tariff_version tf ON tf.energy_type_code=s.energy_type_code AND tf.tou_period='flat' AND tf.effective_from<=to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AND (tf.effective_to IS NULL OR tf.effective_to>=CAST(date_trunc('month',CAST(to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AS timestamp)) + INTERVAL '1 month' - INTERVAL '1 day' AS date))
                    LEFT JOIN e_tariff_version tv ON tv.energy_type_code=s.energy_type_code AND tv.tou_period='valley' AND tv.effective_from<=to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AND (tv.effective_to IS NULL OR tv.effective_to>=CAST(date_trunc('month',CAST(to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AS timestamp)) + INTERVAL '1 month' - INTERVAL '1 day' AS date))
                    LEFT JOIN e_tariff_version to1 ON to1.energy_type_code=s.energy_type_code AND to1.tou_period='flat_only' AND to1.effective_from<=to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AND (to1.effective_to IS NULL OR to1.effective_to>=CAST(date_trunc('month',CAST(to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AS timestamp)) + INTERVAL '1 month' - INTERVAL '1 day' AS date))
                    LEFT JOIN e_cost_alloc_rule ar ON ar.effective_from<=to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AND (ar.effective_to IS NULL OR ar.effective_to>=CAST(date_trunc('month',CAST(to_date(CONCAT(s.stat_month,'-01'),'YYYY-MM-DD') AS timestamp)) + INTERVAL '1 month' - INTERVAL '1 day' AS date))
                    """, demoNow, demoNow);
            // REQ-062: same SHA-256 canonical input, independent of database crypto extensions.
            for (var row : jdbc.queryForList("SELECT id,CONCAT_WS('|',object_type,object_id,stat_month,energy_type_code,usage_qty,peak_qty,flat_qty,valley_qty) canonical FROM e_cost_record WHERE signature='pending'")) {
                jdbc.update("UPDATE e_cost_record SET signature=? WHERE id=?", signature(String.valueOf(row.get("canonical"))), row.get("id"));
            }
            created = number("SELECT COUNT(*) FROM e_cost_record");
        }
        int current = number("SELECT COUNT(*) FROM e_cost_record WHERE is_current=1");
        int expected = number("SELECT COUNT(*) FROM e_stat_month");
        int duplicates = number("SELECT COUNT(*) FROM (SELECT stat_month,object_type,object_id,energy_type_code,COUNT(*) c FROM e_cost_record WHERE is_current=1 GROUP BY stat_month,object_type,object_id,energy_type_code HAVING COUNT(*)>1) x");
        int missing = number("SELECT COUNT(*) FROM e_stat_month s LEFT JOIN e_cost_record c ON c.object_type=s.object_type AND c.object_id=s.object_id AND c.stat_month=s.stat_month AND c.energy_type_code=s.energy_type_code AND c.is_current=1 WHERE c.id IS NULL");
        Map<String, Integer> byScope = new LinkedHashMap<>();
        jdbc.queryForList("SELECT object_type,COUNT(*) c FROM e_cost_record WHERE is_current=1 GROUP BY object_type").forEach(r -> byScope.put(String.valueOf(r.get("object_type")), ((Number)r.get("c")).intValue()));
        return Map.of("initialize", Map.of("created", created, "byScope", byScope),
                "currentIntegrity", Map.of("currentRows", current, "expectedRows", expected, "missingKeys", missing, "duplicateCurrentKeys", duplicates));
    }

    private String signature(String input) {
        try { return "COST-SHA256-V1:" + java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private int number(String sql) { Integer n = jdbc.queryForObject(sql, Integer.class); return n == null ? 0 : n; }
}
