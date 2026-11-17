package com.bproject.ehm.maintenance.application;
import com.bproject.ehm.caseflow.domain.model.*;
import com.bproject.ehm.asset.application.AssetQueryFacade;
import com.bproject.ehm.maintenance.ports.*;
import com.bproject.ehm.shared.error.DomainConflictException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RepairEvidenceGateTest {
 final Instant now=Instant.parse("2026-11-16T08:00:00Z");
 ExecutionRecord execution(Instant time){return new ExecutionRecord("X","检查","已处理","隔离确认","未用备件","维修员",time);}
 RetestRecord test(String point,boolean passed,Instant time){return new RetestRecord("R",point,4.0,2.0,"A","人工确认的规定工况",passed,"复测员",time);}
 WorkOrderRepairService.Repair record(List<ExecutionRecord> e,List<RetestRecord> r){return new WorkOrderRepairService.Repair("W","EQ",List.of("P1","P2"),e,r,null,null,null,null,null);}
 @Test void everyRequiredPointMustHaveLatestPassingResult(){
  assertThrows(DomainConflictException.class,()->WorkOrderRepairService.requirePassing(record(List.of(execution(now)),List.of(test("P1",true,now)))));
  assertThrows(DomainConflictException.class,()->WorkOrderRepairService.requirePassing(record(List.of(execution(now)),List.of(test("P1",true,now),test("P2",true,now),test("P2",false,now.plusSeconds(1))))));
  assertDoesNotThrow(()->WorkOrderRepairService.requirePassing(record(List.of(execution(now)),List.of(test("P1",true,now),test("P2",true,now)))));
 }
 @Test void oldRoundPassesCannotCloseNewRepairRound(){assertThrows(DomainConflictException.class,()->WorkOrderRepairService.requirePassing(record(List.of(execution(now.plusSeconds(10))),List.of(test("P1",true,now),test("P2",true,now)))));}
 @Test void genericStatusEndpointCannotBypassVerification(){var repo=mock(WorkOrderRepository.class);var app=new WorkOrderApplicationService(repo,mock(WorkOrderIdGenerator.class),mock(AssetQueryFacade.class));
  assertThrows(DomainConflictException.class,()->app.transition("W","CLOSED","user","只改状态"));verifyNoInteractions(repo);}
}
