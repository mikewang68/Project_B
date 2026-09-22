package com.bdemo.integration;

import com.bdemo.energy.pipeline.PipelineService;
import com.bdemo.energy.overview.OverviewService;
import com.bdemo.energy.quality.RawQualityService;
import com.bdemo.energy.profile.EquipmentProfileService;
import com.bdemo.energy.cost.CostService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import static org.junit.jupiter.api.Assertions.*;

/** REQ-010~062: opt-in real-database migration regression; use an isolated SQL snapshot. */
@SpringBootTest
@EnabledIfEnvironmentVariable(named="EMCS_DB_TEST", matches="true")
@Transactional
class OpenGaussIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired com.bdemo.auth.CaptchaService captcha;
    @Autowired org.springframework.data.redis.core.StringRedisTemplate redis;
    @Autowired com.bdemo.monitor.MonitorController monitor;
    @Autowired PipelineService pipeline;
    @Autowired OverviewService overview;
    @Autowired RawQualityService quality;
    @Autowired EquipmentProfileService profiles;
    @Autowired CostService cost;
    @Autowired com.bdemo.energy.suggestion.SuggestionService suggestions;
    @Autowired com.bdemo.energy.report.ReportService reports;
    @Autowired com.bdemo.energy.agent.AgentService agent;

    @Test void kvrocksCaptchaAndIsolation() {
        String foreignKey = "other-system:test:"+java.util.UUID.randomUUID();
        var cap = captcha.create();
        String key = "emcs:captcha_codes:"+cap.uuid();
        try {
            redis.opsForValue().set(foreignKey,"keep",java.time.Duration.ofMinutes(2));
            String answer=redis.opsForValue().get(key);
            assertNotNull(answer);
            assertTrue(redis.getExpire(key)>0 && redis.getExpire(key)<=120);
            assertTrue(captcha.consume(cap.uuid(),answer));
            assertFalse(captcha.consume(cap.uuid(),answer));
            assertThrows(IllegalArgumentException.class,()->monitor.clearKey(foreignKey));
            monitor.clearAll();
            assertEquals("keep",redis.opsForValue().get(foreignKey));
        } finally { redis.delete(java.util.List.of(key,foreignKey)); }
    }

    @Test void fullPipelineAndReadModels() {
        assertEquals(564480, jdbc.queryForObject("SELECT COUNT(*) FROM e_raw_reading", Integer.class));
        assertEquals(12, jdbc.queryForObject("SELECT COUNT(*) FROM e_equipment", Integer.class));
        assertFalse(pipeline.rebuildAll().isEmpty());
        assertFalse(pipeline.baselineStatus(true).isEmpty());
        assertNotNull(pipeline.costIntegrity());
        assertFalse(pipeline.ruleStatus().isEmpty());
        assertTrue(jdbc.queryForObject("SELECT COUNT(*) FROM e_alert_event", Integer.class)>0);
        assertFalse(overview.summary("today","ALL","ELEC").isEmpty());
        assertFalse(quality.summary(null,null,null,"ALL").isEmpty());
        assertFalse(profiles.list("ALL","ELEC",null,null).isEmpty());
        assertFalse(cost.monthView("2026-07","ALL","electricity","area",null).isEmpty());
        assertNotNull(suggestions.createTemplate(java.util.Map.of("templateCode","TEST-OG","templateName","openGauss test","category","operation","applicableObjectType","equipment","actionContent","test")));
        assertNotNull(suggestions.create(java.util.Map.of("title","test","measureContent","test","objectType","equipment","objectId",1,"areaId",1),"energy_mgr"));
        var request = java.util.Map.<String,Object>of("templateCode","ENERGY_MONTHLY","period","2026-06");
        assertNotNull(reports.preview(request));
        assertTrue(reports.export(request).content().length > 1000);
        assertNotNull(reports.archive(request,"energy_mgr"));
        assertTrue(agent.run("manual") > 0);
        assertNotNull(cost.recompute(java.util.Map.of("statMonth","2026-07","energyType","electricity","triggerReason","integration test"),"energy_mgr"));
        // Rebuild exercises openGauss upsert update path, not only an empty-table insert.
        assertFalse(pipeline.rebuildRange(java.time.LocalDateTime.parse("2026-07-12T00:00:00"),java.time.LocalDateTime.parse("2026-07-13T00:00:00")).isEmpty());
    }
}
