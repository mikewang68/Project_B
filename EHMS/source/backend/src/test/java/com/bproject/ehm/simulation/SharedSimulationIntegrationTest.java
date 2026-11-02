package com.bproject.ehm.simulation;
import com.bproject.ehm.asset.domain.model.Asset;
import com.bproject.ehm.monitoring.adapter.out.opengemini.OpenGeminiTelemetryAdapter;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.Instant;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:shared-sim;DB_CLOSE_DELAY=-1",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.audit.endpoint=","ehm.ai.enabled=false",
 "ehm.sample-data.enabled=false","ehm.telemetry.adapter=openGemini","ehm.simulation.enabled=true",
 "ehm.simulation.mode=shared-synthetic","ehm.simulation.shared-data-approved=true",
 "ehm.simulation.project-id=PROJECT_B","ehm.project.default-id=PROJECT_B",
 "ehm.simulation.initial-delay-ms=3600000","ehm.config.path=target/no-shared-sim-config.json"})
@ActiveProfiles("server")
class SharedSimulationIntegrationTest {
 @Autowired SimulationService sim;@Autowired OpenGaussJsonStore store;@Autowired JdbcTemplate db;
 @MockBean OpenGeminiTelemetryAdapter real;
 @BeforeEach void reset(){db.update("DELETE FROM ehm.aggregate_store");sim.initializeController();clearInvocations(real);}
 @Test void sharedBootstrapPreservesExistingAssetsAndDoesNotWriteRealTelemetry(){
  var existing=Asset.create("REAL-DEVICE","Existing record","type","real area",null,"original team",Instant.now());
  store.save("asset",existing.code(),existing);
  new SimulationBootstrap(sim,true).ready();
  assertEquals(existing,store.find("asset",existing.code(),Asset.class).orElseThrow());assertEquals(30,store.count("asset"));
  assertTrue(((SimulationService.Control)sim.status().get("control")).running());
  sim.tick("shared-test-round",SimulationService.CONFIRMATION);assertEquals(87,store.count("sample-telemetry"));
  verify(real,never()).write(any());
  sim.running(false,SimulationService.CONFIRMATION);new SimulationBootstrap(sim,true).ready();
  assertFalse(((SimulationService.Control)sim.status().get("control")).running());
  assertEquals("EXISTING_SERVER_AUDIT_POLICY_UNCHANGED",sim.status().get("auditPolicy"));
 }
 @Test void conflictingSyntheticAssetCannotBeReplaced(){
  var original=Asset.create("SAMPLE-LIVE-EQ-IMG-01","Existing owner record","type","area",null,"owner",Instant.now());
  store.save("asset",original.code(),original);
  assertThrows(IllegalStateException.class,()->sim.install(SimulationService.CONFIRMATION));
  assertEquals(original,store.find("asset",original.code(),Asset.class).orElseThrow());assertEquals(1,store.count("asset"));
  assertEquals(0,store.count("measurement-point"));
 }
}
