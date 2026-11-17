package com.bproject.ehm.maintenance.application;

import com.bproject.ehm.asset.domain.model.*;
import com.bproject.ehm.asset.application.DeviceDossierService;
import com.bproject.ehm.alarm.domain.model.*;
import com.bproject.ehm.caseflow.application.*;
import com.bproject.ehm.health.application.HealthAssessmentApplicationService;
import com.bproject.ehm.health.adapter.in.web.DeviceHealthSummaryController;
import com.bproject.ehm.health.domain.model.*;
import com.bproject.ehm.maintenance.domain.model.*;
import com.bproject.ehm.platform.persistence.opengauss.OpenGaussJsonStore;
import com.bproject.ehm.platform.security.*;
import com.bproject.ehm.workbench.application.WorkbenchApplicationService;
import com.bproject.ehm.shared.error.*;
import com.bproject.iam.context.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:requirements-workflows;DB_CLOSE_DELAY=-1;LOCK_TIMEOUT=10000","spring.datasource.driver-class-name=org.h2.Driver",
 "spring.datasource.username=sa","spring.datasource.password=","ehm.persistence.auto-ddl=true","ehm.auth.mode=disabled","ehm.audit.endpoint=",
 "ehm.ai.enabled=false","ehm.sample-data.enabled=false","ehm.simulation.enabled=false","ehm.project.default-id=PROJECT_B","ehm.config.path=target/no-requirements-test-config.json"})
@ActiveProfiles("server")
class RequirementsWorkflowIntegrationTest {
 @SpyBean OpenGaussJsonStore store;@Autowired JdbcTemplate db;
 @Autowired WorkOrderApplicationService orders;@Autowired WorkOrderRepairService repair;@Autowired MaintenanceReminderService reminders;
 @Autowired DeviceDossierService dossiers;@Autowired WorkbenchApplicationService workbench;@Autowired HealthAssessmentApplicationService health;
 @Autowired DeviceHealthSummaryController summaries;@Autowired CaseFlowApplicationService cases;@Autowired CaseEvidenceService evidence;
 @Autowired com.bproject.ehm.asset.application.AssetQueryFacade assets;
 @Autowired org.springframework.context.ApplicationContext context;
 @BeforeEach void seed(){reset(store);org.springframework.web.context.request.RequestContextHolder.resetRequestAttributes();db.update("DELETE FROM ehm.aggregate_store");var now=Instant.now();
  store.save("asset","EQ-1",Asset.create("EQ-1","[测试]设备1","测试","测试区",null,"测试班",now));
  store.save("asset","EQ-2",Asset.create("EQ-2","[测试]设备2","测试","测试区",null,"测试班",now));
  store.save("measurement-point","P1",MeasurementPoint.create("P1","EQ-1","C1","电流","电流","A","TEST","1",5,0.0,10.0,true,now));
  store.save("measurement-point","P2",MeasurementPoint.create("P2","EQ-1","C1","电流2","电流2","A","TEST","2",5,0.0,10.0,true,now));
  store.save("measurement-point","OTHER",MeasurementPoint.create("OTHER","EQ-2","C2","温度","温度","℃","TEST","3",5,0.0,100.0,true,now));
 }
 String order(){return orders.create(new WorkOrderCommand("EQ-1","真实检查任务","P2","测试班","人工","实际检查","待协调","creator")).orderNo();}
 void begin(String no){orders.transition(no,"APPROVED","approver","已确认作业窗口");orders.transition(no,"IN_PROGRESS","mechanic","作业许可已核查");}
 @Test void manualOrderHasFullPersistedExecutionRetestAndRecoveryHistory(){
  String no=order();begin(no);repair.plan(no,List.of("P1","P2"));repair.execute(no,"检查接线","接线已处理","已核验隔离许可","未使用备件","mechanic");orders.transition(no,"WAITING_VERIFY","mechanic","维修完成");
  repair.retest(no,"P1",8d,4d,"A","同工况低于配置阈值",true,"tester");assertThrows(DomainConflictException.class,()->repair.close(no,"关闭","AVAILABLE","通过","reviewer"));
  repair.retest(no,"P2",8d,7d,"A","同工况低于配置阈值",false,"tester");assertThrows(DomainConflictException.class,()->repair.close(no,"关闭","AVAILABLE","通过","reviewer"));
  repair.retest(no,"P2",8d,3d,"A","同工况低于配置阈值",true,"tester");var done=repair.close(no,"本轮合格","RESTRICTED","限载观察，交调度确认","reviewer");
  assertEquals("RESTRICTED",done.recoveryDecision());assertEquals("reviewer",repair.get(no).reviewer());assertEquals(WorkOrderStatus.CLOSED,store.find("work-order",no,WorkOrder.class).orElseThrow().status());
  assertEquals(3,repair.get(no).retests().size());assertEquals(1,store.count("asset")-1);assertTrue(store.find("device-snapshot","EQ-1",Object.class).isEmpty());
 }
 @Test void repairRejectsCrossDeviceWrongUnitAndGenericClosing(){String no=order();begin(no);
  assertThrows(ValidationException.class,()->repair.plan(no,List.of("OTHER")));repair.plan(no,List.of("P1"));repair.execute(no,"实际动作","实际结果","安全确认","无","mechanic");orders.transition(no,"WAITING_VERIFY","mechanic","提交");
  assertThrows(ValidationException.class,()->repair.retest(no,"P1",2d,1d,"℃","依据",true,"tester"));assertThrows(DomainConflictException.class,()->orders.transition(no,"CLOSED","reviewer","跳过"));
 }
 DeviceDossier basis(){var now=Instant.now();var d=new DeviceDossier("EQ-1","","","","","","","",
   new DeviceDossier.MaintenanceBasis("DOC-1","测试维护说明第1节","SIMULATED","DAYS",1,0,now.minusSeconds(2*86400),null,null,"责任班"),
   List.of(new DeviceDossier.DeviceDocument("DOC-1","测试维护依据","MAINTENANCE","fixture","fixture.txt","text/plain",1,"fixture","ACTIVE",now)),0,now);store.save("asset-dossier","EQ-1",d);return d;}
 @Test void dueReminderIsIdempotentAndCannotBeCompletedByCheckbox(){basis();var first=reminders.scan("EQ-1","planner");var second=reminders.scan("EQ-1","planner");assertEquals(true,first.get("created"));assertEquals(false,second.get("created"));
  assertEquals(1,reminders.list("EQ-1").size());var r=reminders.list("EQ-1").get(0);assertThrows(DomainConflictException.class,()->workbench.completeTask(r.taskNo(),"bypass"));assertThrows(ValidationException.class,()->reminders.complete(r.reminderId(),"missing",null,"覆盖说明","reviewer"));
 }
 @Test void qualifyingInspectionCompletesReminderAndAdvancesOnlyItsCycle(){basis();reminders.scan("EQ-1","planner");var r=reminders.list("EQ-1").get(0);var now=Instant.now().plusMillis(1);
  var plan=MaintenancePlan.create("PLAN-1","EQ-1","设备1",null,"周期检查","PERIODIC",1,null,List.of(new InspectionTemplateItem("ITEM-1","检查","实测","符合确认标准","A",true)),"责任班",now,now);
  var task=InspectionTask.create("INS-1",plan,now,"mechanic",now).start("mechanic",now).submit(List.of(new InspectionTask.ItemSubmission("ITEM-1","2","PASS","已核验")),"全部通过","mechanic",now);
  store.save("inspection-task","INS-1",task);var done=reminders.complete(r.reminderId(),"INS-1",null,"已核查检查项覆盖维护说明第1节要求","reviewer");assertEquals("COMPLETED",done.status());assertEquals(now,dossiers.get("EQ-1").maintenanceBasis().lastCompletedAt());
  assertEquals("NOT_DUE",reminders.scan("EQ-1","planner").get("due") instanceof Map<?,?> m?m.get("status"):null);assertEquals(done,reminders.complete(r.reminderId(),"INS-1",null,"已核查覆盖","reviewer"));
 }
 HealthAssessment assessment(String id,Instant expiry){var now=Instant.now();return HealthAssessment.completed(id,"EQ-1","设备1",70,70,"规则评估","rule-v1","feature-v1",100,List.of(),RulPrediction.unavailable("尚未验证"),List.of(),now,now,expiry);}
 @Test void staleAndReplayResultsCannotBecomeCurrentDecisions(){store.save("health-assessment","H1",assessment("H1",Instant.now().minusSeconds(1)));
  assertThrows(DomainConflictException.class,()->health.review("H1","ACCEPTED","核验","reviewer"));var summary=summaries.get("EQ-1");assertEquals("STALE",summary.status());assertNull(summary.score());assertNull(summary.grade());
  assertEquals("UNKNOWN",summaries.get("EQ-2").status());
 }
 @Test void acceptedAssessmentConversionIsCrossThreadIdempotentAndTransactional()throws Exception{
  store.save("health-assessment","H2",assessment("H2",Instant.now().plusSeconds(600)).review("ACCEPTED","人工接受核查","reviewer",Instant.now()));
  var pool=Executors.newFixedThreadPool(4);try{var results=new ArrayList<Future<String>>();for(int i=0;i<6;i++)results.add(pool.submit(()->health.createWorkOrder("H2",null,"班组","明天","planner").workOrderNo()));
   var ids=new HashSet<String>();for(var result:results)ids.add(result.get(15,TimeUnit.SECONDS));assertEquals(1,ids.size());assertEquals(1,store.count("work-order"));
  }finally{pool.shutdownNow();}
 }
 @Test void failedAssessmentLinkRollsBackCreatedOrder(){store.save("health-assessment","H3",assessment("H3",Instant.now().plusSeconds(600)).review("ACCEPTED","人工核验","reviewer",Instant.now()));
  doThrow(new IllegalStateException("synthetic association failure")).when(store).save(eq("health-assessment"),eq("H3"),any(HealthAssessment.class));
  assertThrows(IllegalStateException.class,()->health.createWorkOrder("H3",null,"班组","明天","planner"));assertEquals(0,store.count("work-order"));reset(store);assertNull(health.latest("EQ-1").workOrderNo());
 }
 @Test void alarmCaseRequiresOwnPlanAndSavesRecoverySeparately(){var now=Instant.now();store.save("alarm","A1",new Alarm("A1","EQ-1","设备1","部件","L2","warn","异常",AlarmStatus.NEW,"待确认","TEST_RULE",now,null,null,"班组",List.of(),null));
  cases.openEvidenceCase("A1");cases.diagnose("A1","人工检查","待核原因","人工分析","已查证据","engineer");var no=cases.convertToWorkOrder("A1","检查","P2","班组","按依据处置","明天","planner").workOrderNo();begin(no);
  assertThrows(ValidationException.class,()->cases.defineRetestPlan(no,List.of("OTHER"),"tester"));cases.defineRetestPlan(no,List.of("P1"),"tester");cases.recordExecution(no,"处置","已处理","已隔离","无","mechanic");orders.transition(no,"WAITING_VERIFY","mechanic","完成");
  cases.recordRetest(no,"P1",8d,2d,"A","同工况核查",true,"tester");var done=cases.closeWithRecovery(no,"复核通过","AVAILABLE","全部规定复测通过，不代表自动启动设备","reviewer");
  assertTrue(done.evidence().stream().anyMatch(e->e.type().equals("RECOVERY_DECISION")));assertEquals(AlarmStatus.CLOSED,store.find("alarm","A1",Alarm.class).orElseThrow().status());
 }
 @Test void summaryAndNewAggregatesCannotLeakIntoAnotherRegion()throws Exception{basis();reminders.scan("EQ-1","planner");
  var tuple=new ScopeTriple("PROJECT_B","REGION_OTHER","EHMS");var sql=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(tuple));
  var c=new UserContext(2,"fixture","fixture","fixture","fixture","L4_OPERATOR",false,"EHMS","PROJECT_B","REGION_OTHER","EHMS",ScopeSql.Mode.SCOPED,List.of(tuple),List.of(tuple),Set.of(),Set.of(),Map.of(),Set.of(),"1",sql.my_info(),sql.my_project_zone_info(),sql.my_info_template(),sql.my_project_zone_info_template(),sql.scope_params());
  try(var scope=EhmCentralScope.bind(c)){assertThrows(ResourceNotFoundException.class,()->summaries.get("EQ-1"));assertTrue(store.findAll("maintenance-reminder",MaintenanceReminderService.Reminder.class).isEmpty());}
 }
 @Test void evidenceWindowAndRevisionKeepOriginalFactsAndNeverUseAnotherDevice(){var now=Instant.now();
  store.save("alarm","A1",new Alarm("A1","EQ-1","设备1","部件","L2","warn","异常",AlarmStatus.NEW,"待确认","TEST_RULE",now,null,null,"班组",List.of(),null));cases.openEvidenceCase("A1");
  store.save("measurement-point","MQ-SIM-P1",MeasurementPoint.create("MQ-SIM-P1","EQ-1","C1","电流","电流","A","TEST","1",5,null,null,true,now));
  var point=new LinkedHashMap<String,Object>();point.put("value",com.fasterxml.jackson.databind.node.NullNode.getInstance());point.put("quality","BAD");point.put("unit","A");
  store.save("committed-observation","OBS-1",Map.of("pointCode","MQ-SIM-P1","assetCode","EQ-1","eventId","EVENT-1","simulated",true,"sourceTimestamp",now.toString(),"rawPoint",point));
  assertThrows(ValidationException.class,()->evidence.capture("A1","OTHER",now.minusSeconds(60),now.plusSeconds(60),"analyst"));
  var captured=evidence.capture("A1","MQ-SIM-P1",now.minusSeconds(60),now.plusSeconds(60),"analyst");
  assertEquals(1,captured.returnedRows());assertTrue(captured.observations().get(0).path("rawPoint").path("value").isNull());assertEquals("EVENT-1",captured.observations().get(0).path("eventId").asText());assertEquals(64,captured.sha256().length());
  var original=cases.diagnose("A1","原结论","待核原因","人工","EV证据","analyst").diagnoses().get(0);
  var revised=evidence.revise("A1",original.diagnosisId(),"补查后的结论","已核原因","人工","新证据","现场检查新增依据","reviewer");
  assertEquals("原结论",revised.diagnoses().get(0).conclusion());assertEquals(2,revised.diagnoses().size());assertTrue(revised.evidence().stream().anyMatch(e->e.type().equals("DIAGNOSIS_REVISION")));assertEquals(captured.sha256(),evidence.get(captured.evidenceId()).sha256());
 }
 @Test void optionalDemoJobIsDisabledByDefaultAndNeverScansHistoricalUnassigned()throws Exception{
  assertTrue(context.getBeansOfType(DemoMaintenanceReminderJob.class).isEmpty());basis();
  try(var p=ProjectContext.bind("PROJECT_B");var s=com.bproject.ehm.simulation.SimulationRunScope.bindDemo()){
   store.save("asset","EQ-1",Asset.create("EQ-1","虚拟设备","测试","演示",null,"责任班",Instant.now()));basis();
  }
  new DemoMaintenanceReminderJob(assets,dossiers,reminders,store).scan();assertTrue(reminders.list("EQ-1").isEmpty());
  try(var p=ProjectContext.bind("PROJECT_B");var s=com.bproject.ehm.simulation.SimulationRunScope.bindDemo()){
   assertEquals(1,reminders.list("EQ-1").size());assertEquals("ehms-demo-maintenance-reminders",reminders.list("EQ-1").get(0).createdBy());
  }
 }
}
