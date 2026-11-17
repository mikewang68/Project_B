package com.bproject.ehm.platform.security;
import com.bproject.ehm.alarm.adapter.in.web.AlarmController;
import com.bproject.ehm.alarm.application.AlarmApplicationService;
import com.bproject.ehm.maintenance.adapter.in.web.WorkOrderController;
import com.bproject.ehm.maintenance.application.*;
import com.bproject.ehm.platform.audit.adapter.in.web.IamAuditIdentityFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
class RequirementsIdentityTest {
 @Test void forgedAlarmOperatorIsIgnoredAndAbsentSessionRejected(){
  var app=mock(AlarmApplicationService.class);var web=new AlarmController(app);var http=new MockHttpServletRequest();http.setUserPrincipal(()->"real-operator");
  web.close("A",new AlarmController.OperatorRequest("forged-supervisor","现场核验完成"),http);
  verify(app).close("A","real-operator","现场核验完成");
  http.setUserPrincipal(null);assertThrows(org.springframework.web.server.ResponseStatusException.class,()->web.acknowledge("A",null,http));
 }
 @Test void forgedWorkOrderCreatorIsIgnored(){var service=mock(WorkOrderApplicationService.class);var http=new MockHttpServletRequest();http.setUserPrincipal(()->"verified");
  new WorkOrderController(service).create(new WorkOrderController.CreateRequest("EQ","任务","P2","组","人工","检查","明天","forged"),http);
  var command=org.mockito.ArgumentCaptor.forClass(WorkOrderCommand.class);verify(service).create(command.capture());assertEquals("verified",command.getValue().operator());}
 @Test void readOnlyTrustedIdentityCannotSign(){var http=new MockHttpServletRequest();http.setUserPrincipal(()->"auditor");http.setAttribute(IamAuditIdentityFilter.VERIFIED_READ_ONLY,true);
  assertThrows(org.springframework.web.server.ResponseStatusException.class,()->EhmOperationIdentity.require(http));}
 @Test void newRoutesNeverGrantUnknownSuffixOrUnrelatedMenu(){
  assertEquals("CONFIRM",EhmFeatureRoutes.resolve("POST","/api/ehm/v1/work-orders/W/repair/close").action());
  assertEquals("VIEW",EhmFeatureRoutes.resolve("GET","/api/ehm/v1/devices/E/health-summary").action());
  assertNull(EhmFeatureRoutes.resolve("POST","/api/ehm/v1/work-orders/W/repair/delete-all"));
  assertNull(EhmFeatureRoutes.resolve("POST","/api/ehm/v1/devices/E/health-summary"));
 }
}
