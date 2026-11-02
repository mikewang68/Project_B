package com.bproject.ehm.simulation;

import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.ProjectContext;
import com.bproject.ehm.alarm.domain.model.*;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.workbench.domain.model.UserTask;
import com.bproject.ehm.monitoring.domain.model.DeviceSnapshot;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.context.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:sim-test;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "ehm.persistence.auto-ddl=true","ehm.auth.mode=local","ehm.auth.cookie-name=SIM_TEST","ehm.auth.cookie-secure=false",
 "ehm.audit.iam-required=false","ehm.audit.endpoint=","ehm.ai.enabled=false","ehm.sample-data.enabled=true",
 "ehm.telemetry.adapter=sample","ehm.simulation.enabled=true","ehm.simulation.project-id=SIM_TEST",
 "ehm.project.default-id=SIM_TEST","ehm.simulation.initial-delay-ms=3600000","ehm.config.path=target/no-sim-config.json"})
@ActiveProfiles("server") @AutoConfigureMockMvc
class SimulationIntegrationTest {
 @Autowired SimulationService sim; @Autowired OpenGaussJsonStore store; @Autowired JdbcTemplate db;
 @Autowired TelemetrySeriesPort telemetry; @Autowired MockMvc mvc; @Autowired ObjectMapper mapper;
 static Path bootstrap; static final String PASSWORD="SyntheticSimulationTest-2026!";
 static final String C=SimulationService.CONFIRMATION,ASSET="SAMPLE-LIVE-EQ-IMG-01";
 @DynamicPropertySource static void props(DynamicPropertyRegistry p)throws Exception{
  bootstrap=Files.createTempFile("sim-test-",".json");
  Files.writeString(bootstrap,"[{\"username\":\"simadmin\",\"displayName\":\"Test Admin\",\"password\":\""+PASSWORD+"\",\"role\":\"SUPER_ADMIN\"},{\"username\":\"simviewer\",\"displayName\":\"Test Viewer\",\"password\":\""+PASSWORD+"\",\"role\":\"USER\"}]");
  p.add("ehm.auth.bootstrap-file",()->bootstrap.toString());
 }
 @AfterAll static void cleanup()throws Exception{Files.deleteIfExists(bootstrap);}
 @BeforeEach void reset(){db.update("DELETE FROM ehm.aggregate_store");sim.initializeController();}
 SimulationService.Control control(){return (SimulationService.Control)sim.status().get("control");}
 void install(){sim.install(C);sim.configure(SimulationService.Settings.demo(),C);}
 SimulationService.Round tick(int n){return (SimulationService.Round)sim.tick("test-round-"+n,C).get("round");}
 @Test void fullCatalogInsertOnlyAndDefaults(){
  var catalog=(SimulationCatalog)sim.status().get("catalog");assertEquals(29,catalog.entries().size());
  sim.install(C);assertEquals(29,store.count("asset"));assertEquals(87,store.count("measurement-point"));
  assertFalse(control().running());assertFalse(control().settings().autoWorkflow());
  sim.install(C);assertEquals(29,store.count("asset"));assertEquals(0,store.count("sample-telemetry"));
  assertFalse((Boolean)sim.status().get("externalDelivery"));assertEquals(29,store.count("maintenance-plan"));
 }
 @Test void thresholdIdempotenceAndFiveRoundClosedLoop(){
  install();var first=tick(1);assertEquals(87,first.samplesWritten());assertTrue(first.alarmsCreated()>0);
  String id=ASSET+"-C1-ALM",order=ASSET+"-C1-WO";
  assertEquals(AlarmStatus.NEW,store.find("alarm",id,Alarm.class).orElseThrow().status());
  assertTrue((Boolean)sim.tick("test-round-1",C).get("replayed"));assertEquals(1,control().ticks());
  for(int n=2;n<=5;n++)tick(n);
  assertEquals(WorkOrderStatus.CLOSED,store.find("work-order",order,WorkOrder.class).orElseThrow().status());
  assertEquals(AlarmStatus.CLOSED,store.find("alarm",id,Alarm.class).orElseThrow().status());
  assertEquals("COMPLETED",store.find("user-task",ASSET+"-C1-TODO",UserTask.class).orElseThrow().status());
  var p=store.find("simulation-progress",ASSET,SimulationService.Progress.class).orElseThrow();
  assertEquals(0,p.serviceHours());assertEquals(33.5,p.totalHours());assertEquals(2,p.cycle());
  assertEquals(5,telemetry.history(ASSET+"-TEMP",null,null,100).size());
  assertTrue(db.queryForObject("SELECT COUNT(*) FROM ehm.audit_outbox WHERE payload LIKE '%ehm-simulator%'",Integer.class)>0);
  assertEquals(29,store.count("inspection-task"));assertEquals(1,store.count("configuration-change"));
  assertEquals(4,store.count("spare-part"));assertTrue(store.count("stock-transaction")>4);
  assertTrue(store.count("knowledge-case")>0);assertTrue(store.count("spare-reservation")>0);
 }
 @Test void communicationOutageBlocksScoreThenRecovers(){
  install();for(int n=1;n<=12;n++)tick(n);
  String a="SAMPLE-LIVE-EQ-IMG-17",alarm="SAMPLE-LIVE-DATA-0-EQ-IMG-17";
  assertNull(store.find("device-snapshot",a,DeviceSnapshot.class).orElseThrow().health());
  assertEquals(AlarmStatus.NEW,store.find("alarm",alarm,Alarm.class).orElseThrow().status());
  for(int n=13;n<=15;n++)tick(n);
  assertNotNull(store.find("device-snapshot",a,DeviceSnapshot.class).orElseThrow().health());
  assertEquals(AlarmStatus.CLOSED,store.find("alarm",alarm,Alarm.class).orElseThrow().status());
 }
 @Test void pauseScheduleAndPersistedState(){
  install();sim.scheduledTick();assertEquals(0,control().ticks());sim.running(true,C);
  sim.scheduledTick();assertEquals(1,control().ticks());sim.scheduledTick();assertEquals(1,control().ticks());
  sim.initializeController();assertTrue(control().running());assertEquals(1,control().ticks());
  sim.running(false,C);sim.scheduledTick();assertEquals(1,control().ticks());
 }
 @Test void longIntervalsAlsoAdjustFreshnessAndKeepOutageDetectable(){
  install();sim.configure(new SimulationService.Settings(43200,12,40,.8,false),C);
  var point=store.find("measurement-point",ASSET+"-TEMP",com.bproject.ehm.asset.domain.model.MeasurementPoint.class).orElseThrow();
  assertEquals(43200,point.sampleIntervalSeconds());
  for(int n=1;n<=12;n++)tick(n);
  assertNull(store.find("device-snapshot","SAMPLE-LIVE-EQ-IMG-17",DeviceSnapshot.class).orElseThrow().health());
 }
 @Test void failuresRollbackWholeRoundAndPersistPause(){
  install();sim.running(true,C);
  db.update("DELETE FROM ehm.aggregate_store WHERE aggregate_type='measurement-point' AND aggregate_id=?",ASSET+"-TEMP");
  assertThrows(RuntimeException.class,()->tick(1));assertEquals(0,store.count("sample-telemetry"));assertEquals(0,store.count("health-assessment"));
  assertEquals(0,control().ticks());assertFalse(control().running());sim.initializeController();
  assertTrue(sim.status().get("lastFailure").toString().contains("ROUND_ROLLED_BACK"));
 }
 @Test void projectsDoNotLeakAndConfirmationRequired()throws Exception{
  install();try(var ignored=ProjectContext.bind("ANOTHER_PROJECT")){
   assertFalse(sim.currentProjectAllowed());assertEquals(0,store.count("asset"));
  }
  assertThrows(IllegalArgumentException.class,()->sim.install("YES"));
  assertThrows(IllegalArgumentException.class,()->sim.tick("short",C));
  assertThrows(IllegalArgumentException.class,()->new SimulationService.Settings(0,1,40,.8,false));
 }
 @Test void concurrentDuplicateTicksAdvanceExactlyOnce()throws Exception{
  install();var pool=Executors.newFixedThreadPool(2);
  try{var a=pool.submit(()->sim.tick("concurrent-round",C));var b=pool.submit(()->sim.tick("concurrent-round",C));
   a.get(30,TimeUnit.SECONDS);b.get(30,TimeUnit.SECONDS);assertEquals(1,control().ticks());assertEquals(87,store.count("sample-telemetry"));
  }finally{pool.shutdownNow();}
 }
 record Session(Cookie cookie,String csrf){}
 Session login(String user)throws Exception{
  var r=mvc.perform(post("/api/ehm/v1/auth/login").contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(Map.of("username",user,"password",PASSWORD)))).andExpect(status().isOk()).andReturn().getResponse();
  String h=r.getHeader("Set-Cookie");return new Session(new Cookie("SIM_TEST",h.substring(h.indexOf('=')+1,h.indexOf(';'))),mapper.readTree(r.getContentAsString()).path("csrfToken").asText());
 }
 @Test void authenticatedAdminAndCsrfAreRequired()throws Exception{
  String url="/api/ehm/v1/system/simulation/install",body="{\"confirmation\":\"SYNTHETIC_ONLY\"}";
  mvc.perform(get("/api/ehm/v1/system/simulation")).andExpect(status().isUnauthorized());
  var viewer=login("simviewer");var admin=login("simadmin");
  mvc.perform(post(url).cookie(viewer.cookie()).header("X-EHM-CSRF",viewer.csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post(url).cookie(admin.cookie()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isForbidden());
  mvc.perform(post(url).cookie(admin.cookie()).header("X-EHM-CSRF",admin.csrf()).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
  mvc.perform(get("/api/ehm/v1/system/simulation").cookie(viewer.cookie())).andExpect(status().isOk());
 }
}
