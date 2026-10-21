package com.bproject.ehm.samples;

import com.bproject.ehm.samples.application.*;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.health.domain.model.HealthAssessment;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.reliability.domain.model.*;
import com.bproject.ehm.workbench.domain.model.*;
import com.bproject.ehm.caseflow.domain.model.MaintenanceCase;
import com.bproject.ehm.spares.domain.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.server.ResponseStatusException;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:sample-data;DB_CLOSE_DELAY=-1","spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=","ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.sample-data.enabled=true","ehm.telemetry.adapter=sample","ehm.ai.enabled=false","ehm.config.path=target/no-sample-config.json","ehm.audit.endpoint="})
@ActiveProfiles("server")
class IndustrialSampleIntegrationTest {
    @Autowired IndustrialSampleService samples;@Autowired OpenGaussJsonStore store;@Autowired TelemetrySeriesPort telemetry;
    @Test void insertOnlyCoherentBundleAndPersistentSyntheticHistory(){
        var real=Asset.create("EXISTING-REAL-01","Existing record","pump","area",null,"owner",Instant.now());store.save("asset",real.code(),real);
        var result=samples.install(IndustrialSampleService.BUNDLE);assertTrue(((Number)result.get("totalInserted")).intValue()>1200);
        assertEquals(real,store.find("asset",real.code(),Asset.class).orElseThrow());
        assertEquals(8,store.findAll("asset",Asset.class).stream().filter(a->a.code().startsWith("SAMPLE-")).count());
        assertEquals(24,store.count("measurement-point"));assertEquals(78,store.count("health-assessment"));
        assertTrue(store.findAll("health-assessment",HealthAssessment.class).stream().allMatch(h->h.prediction().expectedDays()==null));
        assertEquals(8,store.findAll("maintenance-plan",MaintenancePlan.class).size());assertEquals(8,store.findAll("inspection-task",InspectionTask.class).size());
        assertEquals(8,store.findAll("maintenance-case",MaintenanceCase.class).size());assertEquals(3,store.findAll("device-template",DeviceTemplate.class).size());
        assertEquals(8,store.findAll("calibration-record",CalibrationRecord.class).size());assertEquals(8,store.findAll("configuration-change",ConfigurationChange.class).size());
        assertEquals(8,store.findAll("user-task",UserTask.class).size());assertEquals(3,store.findAll("shift-handover",ShiftHandover.class).size());
        assertEquals(3,store.findAll("failure-mode",FailureMode.class).size());assertEquals(3,store.findAll("alarm-rule",AlarmRule.class).size());assertEquals(3,store.findAll("knowledge-case",KnowledgeCase.class).size());
        assertEquals(3,store.findAll("spare-part",SparePart.class).size());assertEquals(3,store.findAll("stock-balance",StockBalance.class).size());
        long seriesCount=store.count("sample-telemetry");assertEquals(1176,seriesCount);
        assertEquals(49,telemetry.history("SAMPLE-GT-01-VIB",Instant.now().minusSeconds(90000),Instant.now(),100).size());
        assertFalse(telemetry.history("SAMPLE-GT-01-VIB",null,null,100).isEmpty());
        var changed=store.find("asset","SAMPLE-GT-01",Asset.class).orElseThrow().update("User modification","门吊","user area",null,"user",Instant.now());store.save("asset",changed.code(),changed);
        assertEquals(0,((Number)samples.install(IndustrialSampleService.BUNDLE).get("totalInserted")).intValue());
        assertEquals(changed,store.find("asset",changed.code(),Asset.class).orElseThrow());assertEquals(seriesCount,store.count("sample-telemetry"));
        assertEquals(21,((Number)samples.sampleNow(IndustrialSampleService.BUNDLE).get("samplesCreated")).intValue());assertEquals(seriesCount+21,store.count("sample-telemetry"));
        assertTrue(telemetry.history("SAMPLE-GT-01-VIB",Instant.now().minusSeconds(90000),Instant.now(),100).stream().allMatch(s->s.quality().startsWith("SYNTHETIC_")));
        assertThrows(IllegalArgumentException.class,()->telemetry.write(new TelemetrySeriesPort.TelemetrySample("REAL-POINT","REAL-ASSET","温度","℃",30.0,"GOOD",Instant.now(),Instant.now())));
        assertThrows(ResponseStatusException.class,()->samples.install("unapproved-bundle"));
    }
    @Test void disabledProductionImportFailsClosed(){
        var disabled=new IndustrialSampleService(store,false,"openGemini","openGauss");
        assertThrows(ResponseStatusException.class,()->disabled.install(IndustrialSampleService.BUNDLE));
        assertThrows(ResponseStatusException.class,()->disabled.sampleNow(IndustrialSampleService.BUNDLE));
        assertFalse((Boolean)disabled.status().get("enabled"));
    }
}
