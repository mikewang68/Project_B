package com.bproject.safety.config;

import com.bproject.iam.context.*;
import java.util.*;
import java.time.Clock;
import org.junit.jupiter.api.*;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScsExtendedScopeTest {
  UserContext context(String menu,Set<String> actions,boolean readonly,String level){
    var t=new ScopeTriple("PROJECT_B","SHEN_TIE","SCS");var f=ScopeSql.build(ScopeSql.Mode.SCOPED,List.of(t));
    return new UserContext(2,"subject","user","subject","user",level,readonly,"SCS","PROJECT_B","SHEN_TIE","SCS",ScopeSql.Mode.SCOPED,List.of(t),List.of(t),Set.of("job"),Set.of(menu),Map.of(menu,actions),Set.of(),"1",f.my_info(),f.my_project_zone_info(),f.my_info_template(),f.my_project_zone_info_template(),f.scope_params());
  }
  void bind(UserContext c){var r=new MockHttpServletRequest("GET","/api/v1/ops/edge-nodes");ScsCentralScope.bind(r,c);RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(r));}
  @AfterEach void clear(){RequestContextHolder.resetRequestAttributes();}
  boolean allows(String menu,String operation,String method,String path){return ScsCentralScope.permits(context(menu,Set.of(operation),false,"L3_ADMIN"),method,"/api/v1/"+path);}
  @Test void eachPublishedPageHasAnExactViewBoundary(){
    Map<String,String> paths=Map.ofEntries(Map.entry("overview","overview/summary"),Map.entry("alarms","alerts"),Map.entry("people","personnel"),Map.entry("devices","collision/devices"),Map.entry("ai","ai-events"),Map.entry("ai-models","ai/models"),Map.entry("operations","ops/overview"),Map.entry("analytics","analytics/dataset"),Map.entry("mobile-home","mobile/home"),Map.entry("mobile-alerts","alerts"),Map.entry("safety-screen","screen/overview"));
    paths.forEach((menu,path)->{assertTrue(allows("SCS:"+menu,"VIEW","GET",path),menu);assertFalse(allows("SCS:rules","VIEW","GET",path),menu);});
  }
  @Test void confirmEditAndAlarmAreNotInterchangeable(){
    assertTrue(allows("SCS:alarms","CONFIRM","POST","alerts/a/confirm"));
    assertFalse(allows("SCS:alarms","EDIT","POST","alerts/a/confirm"));
    assertTrue(allows("SCS:mobile-alerts","EDIT","POST","alerts/a/treatment"));
    assertTrue(allows("SCS:mobile-alerts","CONFIRM","POST","mobile/incidents/a/accept"));
    assertFalse(allows("SCS:mobile-home","CONFIRM","POST","mobile/incidents/a/accept"));
    assertFalse(allows("SCS:ai","FAULT_ALARM","POST","alerts/a/escalate"));
    assertTrue(allows("SCS:alarms","FAULT_ALARM","POST","alerts/a/escalate"));
    assertTrue(allows("SCS:ai","EDIT","POST","ai-events/a/false-positive"));
    assertTrue(allows("SCS:operations","CONFIRM","POST","edge/recover"));
    assertFalse(allows("SCS:operations","EDIT","POST","edge/recover"));
  }
  @Test void globalModelConfigurationRequiresL1AndInferIsOnlyRegisteredReadPost(){
    var writer=context("SCS:ai-models",Set.of("VIEW","EDIT","CONFIRM","FAULT_ALARM"),false,"L3_ADMIN");
    assertFalse(ScsCentralScope.permits(writer,"POST","/api/v1/ai/models/m/select"));
    assertTrue(ScsCentralScope.permits(context("SCS:ai-models",Set.of("CONFIRM"),false,"L1_SUPER"),"POST","/api/v1/ai/models/m/select"));
    var reader=context("SCS:ai-models",Set.of("VIEW","EDIT","CONFIRM","FAULT_ALARM"),true,"L1_READONLY");
    assertTrue(ScsCentralScope.permits(reader,"POST","/api/v1/ai/models/infer"));
    assertFalse(ScsCentralScope.permits(reader,"POST","/api/v1/ai/models/escalate"));
    assertEquals("safety:module:ai:model:view",ScsRoutePermissions.required("POST","/api/v1/ai/models/infer"));
    assertFalse(ScsCentralScope.readOnlyQuery("POST","/api/v1/ai/models/infer/extra"));
    assertFalse(allows("SCS:ai","EDIT","POST","ai-events/trigger-edge"));
  }
  @Test void scopedNodeReadsNeverPersistInventedHeartbeat(){
    var repo=mock(com.bproject.safety.module.ops.repository.EdgeNodeRepository.class);
    var node=new com.bproject.safety.module.ops.model.DemoEdgeNode();node.id="n";node.status="ONLINE";node.uptimeSec=22;
    when(repo.findAll()).thenReturn(List.of(node));when(repo.findById("n")).thenReturn(Optional.of(node));
    var service=new com.bproject.safety.module.ops.service.EdgeOpsService(repo,null,null,null,null,null,null,null,List.of(),Clock.systemUTC());
    bind(context("SCS:operations",Set.of("VIEW"),true,"L4_OPERATOR"));
    assertEquals(22,service.listNodes().get(0).uptimeSec);assertEquals(22,service.getNode("n").uptimeSec);
    verify(repo,never()).save(any());
  }
  @Test void reusedIdempotencyKeyCannotReturnAnotherEndpointsResult(){
    var redis=mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    @SuppressWarnings("unchecked") var values=(org.springframework.data.redis.core.ValueOperations<String,String>)mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var service=new com.bproject.safety.common.idempotency.IdempotencyService(redis,600);
    bind(context("SCS:alarms",Set.of("EDIT"),false,"L4_OPERATOR"));
    service.getCompletedResult("same-client-key");service.getCompletedResult("same-client-key");
    var request=new MockHttpServletRequest("POST","/api/v1/alerts/a/treatment");ScsCentralScope.bind(request,ScsCentralScope.current());RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
    service.getCompletedResult("same-client-key");
    var keys=org.mockito.ArgumentCaptor.forClass(String.class);verify(values,times(3)).get(keys.capture());
    assertEquals(keys.getAllValues().get(0),keys.getAllValues().get(1));assertNotEquals(keys.getAllValues().get(0),keys.getAllValues().get(2));
    assertTrue(keys.getValue().startsWith("idempotency:v2:"));assertFalse(keys.getValue().contains("same-client-key"));
  }
  @Test void cachedCollisionPairNeedsBothDevicesInTheCurrentScope(){
    var repo=mock(com.bproject.safety.module.collision.repository.CollisionRepository.class);
    var service=new com.bproject.safety.module.collision.service.CollisionService(repo,null,null,Clock.systemUTC(),null);service.resetAllPairs();
    bind(context("SCS:devices",Set.of("VIEW","CONFIRM"),false,"L4_OPERATOR"));
    assertThrows(com.bproject.safety.common.error.ApiException.class,()->service.pair("VEH-07",null));
    var device=new com.bproject.safety.module.collision.model.DemoCollisionDevice();device.id="VEH-07";
    when(repo.findById("VEH-07")).thenReturn(Optional.of(device));
    assertThrows(com.bproject.safety.common.error.ApiException.class,()->service.trend("VEH-07"));
    when(repo.findById("TIP-02")).thenReturn(Optional.of(device));assertNotNull(service.pair("VEH-07",null));
  }
}
