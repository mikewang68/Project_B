package com.bproject.ehm.simulation;
import com.bproject.ehm.asset.application.*;
import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.monitoring.adapter.out.opengemini.OpenGeminiTelemetryAdapter;
import com.bproject.ehm.monitoring.ports.TelemetrySeriesPort;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.maintenance.application.*;
import com.bproject.ehm.maintenance.adapter.in.web.MaintenanceCalendarController;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.ProjectContext;
import com.bproject.ehm.shared.page.PageQuery;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import java.time.*;import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:integrated-demo;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=20000",
 "spring.datasource.driver-class-name=org.h2.Driver","spring.datasource.username=sa","spring.datasource.password=",
 "ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.audit.endpoint=","ehm.ai.enabled=false",
 "ehm.sample-data.enabled=false","ehm.telemetry.adapter=openGemini","ehm.simulation.enabled=true",
 "ehm.simulation.mode=shared-synthetic","ehm.simulation.shared-data-approved=true",
 "ehm.simulation.project-id=PROJECT_B","ehm.project.default-id=PROJECT_B","ehm.demo-workspace.enabled=true",
 "ehm.demo-workspace.initial-delay-ms=36000000","ehm.simulation.initial-delay-ms=36000000",
 "ehm.documents.root=target/integrated-demo-documents","ehm.config.path=target/no-integrated-demo-config.json"})
@ActiveProfiles("server")
class DemoWorkspaceIntegrationTest {
 @Autowired DemoWorkspaceService demo;@Autowired SimulationService legacy;@Autowired OpenGaussJsonStore store;
 @Autowired JdbcTemplate db;@Autowired TelemetrySeriesPort telemetry;@Autowired DeviceApplicationService devices;
 @Autowired DeviceDossierService dossiers;@Autowired WorkOrderApplicationService orders;@Autowired WorkOrderRepairService repairs;
 @Autowired MaintenanceCalendarController calendar;
 @MockBean OpenGeminiTelemetryAdapter real;
 static final String EXTRA="SIM-DEMO-ACCEPT-01";
 @BeforeEach void reset() throws Exception{
  org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();
  db.update("DELETE FROM ehm.aggregate_store");legacy.initializeController();clearInvocations(real);
  store.save("asset","REAL-LEGACY",Asset.create("REAL-LEGACY","Unchanged owner data","type","area",null,"owner",Instant.now()));
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   for(int i=1;i<=4;i++){String code="SIM-DEMO-ACCEPT-0"+i;store.save("asset",code,Asset.create(code,"[虚拟]"+code,"virtual","demo",null,"虚拟班",Instant.now()));}
  }
 }
 void run() throws Exception{demo.runOnce();}
 @Test void seedsAllRelatedObjectsWithoutTouchingLegacyAndIsIdempotent() throws Exception{
  run();
  assertEquals(1,store.count("asset"));assertTrue(store.find("asset","REAL-LEGACY",Asset.class).isPresent());
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   assertEquals(33,store.count("asset"));assertEquals(99,store.count("measurement-point"));
   assertEquals(33,store.count("component"));assertTrue(store.count("sample-telemetry")>=2376);
   assertEquals(33,store.count("maintenance-plan"));assertTrue(store.count("work-order")>=6);
   assertEquals(33,store.count("asset-dossier"));assertEquals(33,store.count("maintenance-reminder"));
   assertEquals(1,store.count("defect"));
   assertTrue(store.count("case-evidence-window")>=1);assertTrue(store.count("knowledge-case")>=1);
   long rows=store.count("work-order");run();assertEquals(rows,store.count("work-order"));
   assertEquals(33,store.count("asset"));assertEquals("RUNNING",store.find("demo-workspace","activity",DemoWorkspaceService.RunState.class).orElseThrow().status());
  }
  verify(real,never()).write(any());
 }
 @Test void dossierAndCatalogFollowCreateAndArchiveWithoutLosingRepairHistory() throws Exception{
  run();
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   var created=devices.create(new DeviceCommand("SAMPLE-ACCEPT-NEW","[虚拟]验收新建设备","virtual","demo","待接入",null,"未知","limited",null,"待接入","无",null,"虚拟班",null,null,null,null));
   assertEquals(created.code(),dossiers.get(created.code()).assetCode());
   assertTrue(devices.list(new PageQuery(0,100),"","","").content().stream().anyMatch(a->a.code().equals(created.code())));
   devices.archive(created.code());assertThrows(com.bproject.ehm.shared.error.ResourceNotFoundException.class,()->dossiers.get(created.code()));
   assertFalse(devices.list(new PageQuery(0,100),"","","").content().stream().anyMatch(a->a.code().equals(created.code())));
   long repairsBefore=store.count("work-order-repair");devices.archive(EXTRA);run();
   assertTrue(store.find("asset",EXTRA,Asset.class).orElseThrow().archived());assertEquals(repairsBefore,store.count("work-order-repair"));
  }
 }
 @Test void realRepairCommandsImmediatelyAppearInCalendarAndCannotCloseFailedRetests() throws Exception{
  run();
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   var asset=store.find("asset",EXTRA,Asset.class).orElseThrow();String no="DEMO-MANUAL-CALENDAR";
   store.save("work-order",no,WorkOrder.create(no,EXTRA,asset.name(),"[虚拟]日历联动","P2","虚拟班","TEST","test","today","test-actor",Instant.now()));
   orders.transition(no,"APPROVED","test-actor","approved");orders.transition(no,"IN_PROGRESS","test-actor","started");
   String point="DEMO-POINT-"+EXTRA+"-TEMP";repairs.plan(no,List.of(point));repairs.execute(no,"actual test action","test result","test safety","none","test-actor");
   orders.transition(no,"WAITING_VERIFY","test-actor","verify");repairs.retest(no,point,80d,90d,"℃","test criterion",false,"test-actor");
   assertThrows(com.bproject.ehm.shared.error.DomainConflictException.class,()->repairs.close(no,"no","AVAILABLE","not valid","reviewer"));
   var full=calendar.read(EXTRA,Instant.now().minusSeconds(86400),Instant.now().plusSeconds(86400),true,true);
   var events=(List<MaintenanceCalendarController.Entry>)full.get("events");
   assertTrue(events.stream().anyMatch(e->no.equals(e.orderNo())&&e.kind().equals("EXECUTION")&&e.detail().contains("test safety")));
   assertTrue(events.stream().anyMatch(e->no.equals(e.orderNo())&&e.kind().equals("RETEST")&&e.status().equals("不通过")));
   var limited=(List<MaintenanceCalendarController.Entry>)calendar.read(EXTRA,Instant.now().minusSeconds(86400),Instant.now().plusSeconds(86400),false,false).get("events");
   assertFalse(limited.stream().anyMatch(e->e.orderNo()!=null));
  }
 }
 @Test void regionalWorkerDoesNotStopAtOldLocalExerciseRoundLimit() throws Exception{
  run();
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   var c=store.find("simulation-control","control",SimulationService.Control.class).orElseThrow();
   store.save("simulation-control","control",new SimulationService.Control(true,true,c.runId(),8640,c.simulatedHours(),c.settings(),c.lastTickAt(),Instant.now().minusSeconds(1)));
  }
  run();
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   assertEquals(8641,store.find("simulation-control","control",SimulationService.Control.class).orElseThrow().ticks());
  }
 }
 @Test void existingPublicMqMappingsAreNeverReplayedIntoRealTelemetry() throws Exception{
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   store.save("asset","MQ-SIM-PUBLIC",Asset.create("MQ-SIM-PUBLIC","[虚拟]公共接入设备","virtual","demo",null,"owner",Instant.now()));
   store.save("measurement-point","MQ-SIM-PUBLIC-P1",MeasurementPoint.create("MQ-SIM-PUBLIC-P1","MQ-SIM-PUBLIC","SIM-DEMO-ACCEPT-01-CORE","public point","温度","℃","SYNTHETIC","mq://fixture",60,0d,100d,true,Instant.now()));
  }
  run();verify(real,never()).write(any());
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   assertEquals("SYNTHETIC",store.find("measurement-point","MQ-SIM-PUBLIC-P1",MeasurementPoint.class).orElseThrow().sourceProtocol());
   assertTrue(store.findAll("sample-telemetry",TelemetrySeriesPort.TelemetrySample.class).stream().noneMatch(s->s.pointCode().equals("MQ-SIM-PUBLIC-P1")));
  }
 }
 @Test void syntheticProvenanceAndBadQualitySurviveHistoryReadAndWrongRegionCannotSeeIt() throws Exception{
  run();
  try(var p=ProjectContext.bind("PROJECT_B");var scope=SimulationRunScope.bindDemo()){
   var samples=telemetry.history("DEMO-POINT-"+EXTRA+"-TEMP",Instant.now().minusSeconds(86400),Instant.now().plusSeconds(1),2000);
   assertTrue(samples.stream().anyMatch(s->s.quality().contains("BAD")));assertTrue(samples.stream().allMatch(s->Boolean.TRUE.equals(s.simulated())));
   assertTrue(samples.stream().anyMatch(s->"DEMO_REPLAY_INITIALIZATION".equals(s.observationRef())));
  }
  assertEquals(0,store.count("sample-telemetry"));verify(real,never()).write(any());
 }
}
